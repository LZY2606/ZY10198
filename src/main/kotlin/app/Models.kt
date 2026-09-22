package app

import kotlinx.serialization.Serializable

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

enum class ModelType(val label: String, val dims: Int) {
    MOGI("点源 (Mogi)", 5),
    DIKE("岩墙 (Okada 张性断裂)", 8),
    PLANE("受限平面源 (受限张性平面)", 8),
}

/** 统一参数：点源忽略 L/W/dip/strike/open（以体积变化表示）。 */
data class SourceParams(
    val x: Double,
    val y: Double,
    val depth: Double,       // km，地表向下为正
    val strike: Double,      // deg
    val dip: Double,         // deg
    val length: Double,      // km，沿走向全长
    val width: Double,       // km，沿倾向全宽
    val strength: Double,    // Mogi: dV (m^3)；岩墙/平面: 开口量 open (m)
) {
    fun toArray(type: ModelType): DoubleArray = when (type) {
        ModelType.MOGI -> doubleArrayOf(x, y, depth, strength)
        ModelType.DIKE, ModelType.PLANE ->
            doubleArrayOf(x, y, depth, strike, dip, length, width, strength)
    }

    companion object {
        fun fromArray(type: ModelType, a: DoubleArray): SourceParams = when (type) {
            ModelType.MOGI -> SourceParams(a[0], a[1], a[2], 0.0, 90.0, 0.0, 0.0, a[3])
            ModelType.DIKE, ModelType.PLANE ->
                SourceParams(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7])
        }
    }
}

data class ParamSpec(
    val name: String,
    val key: String,
    val unit: String,
    val lo: Double,
    val hi: Double,
)

/**
 * 物理边界（用户可选）。深度、尺寸均为正值口径（地下深度 / 几何长度）。
 */
@Serializable
data class PhysicalBounds(
    val xMin: Double = -10.0, val xMax: Double = 10.0,
    val yMin: Double = -10.0, val yMax: Double = 10.0,
    val depthMin: Double = 0.05, val depthMax: Double = 12.0,
    val strikeMin: Double = 0.0, val strikeMax: Double = 360.0,
    val dipMin: Double = 0.0, val dipMax: Double = 90.0,
    val lengthMin: Double = 0.2, val lengthMax: Double = 12.0,
    val widthMin: Double = 0.2, val widthMax: Double = 12.0,
    val strengthMin: Double = -1.0e8, val strengthMax: Double = 1.0e8,
) {
    fun specs(type: ModelType): List<ParamSpec> {
        val common = listOf(
            ParamSpec("东向位置", "x", "km", xMin, xMax),
            ParamSpec("北向位置", "y", "km", yMin, yMax),
            ParamSpec("深度(向下为正)", "depth", "km", depthMin, depthMax),
        )
        return when (type) {
            ModelType.MOGI -> common + ParamSpec("体积变化 dV", "strength", "m^3", strengthMin, strengthMax)
            ModelType.DIKE, ModelType.PLANE -> common + listOf(
                ParamSpec("走向", "strike", "deg", strikeMin, strikeMax),
                ParamSpec("倾角", "dip", "deg", dipMin, dipMax),
                ParamSpec("沿走向长度", "length", "km", lengthMin, lengthMax),
                ParamSpec("沿倾向宽度", "width", "km", widthMin, widthMax),
                ParamSpec(
                    if (type == ModelType.DIKE) "开口量 open" else "等效开口量",
                    "strength", "m", strengthMin, strengthMax,
                ),
            )
        }
    }

    fun arrays(type: ModelType): Pair<DoubleArray, DoubleArray> {
        val sp = specs(type)
        return DoubleArray(sp.size) { sp[it].lo } to DoubleArray(sp.size) { sp[it].hi }
    }
}

/** 形变模型：在 ENU 站点处返回三维位移 (m)，u_z 向上为正。 */
interface DeformationModel {
    fun displacement(site: Site, p: SourceParams): Vec3
}

/**
 * Mogi (1958) 点源：均匀弹性半空间中的球对称压力源。
 * 取泊松比 nu=0.25（Mogi 经典口径），表面位移
 *   u_i = (dV / pi) * (x_i - s_i) / R^3,  i = x,y
 *   u_z = (dV / pi) * depth / R^3         （向上为正；dV>0 膨胀 => 抬升）
 * 坐标差与深度统一换算为米。
 */
class MogiModel(private val nu: Double = Conventions.POISSON_RATIO) : DeformationModel {
    private val c = (1.0 - nu) / PI // nu=0.25 时为 3/(4pi)；经典 Mogi 常用 1/pi 口径，
    // 这里保留可配置系数并默认 nu=.25 的弹性力学标准形式 (1-nu)/pi。

    override fun displacement(site: Site, p: SourceParams): Vec3 {
        val dx = (site.x - p.x) * 1000.0
        val dy = (site.y - p.y) * 1000.0
        val dz = p.depth * 1000.0
        val r2 = dx * dx + dy * dy + dz * dz
        val r = sqrt(r2)
        val k = c * p.strength / (r2 * r)
        return Vec3(k * dx, k * dy, k * dz)
    }
}

/** 受限平面源：张性平面上规则子源网格（每个为 Mogi 点膨胀核）。 */
class PlaneGridModel(
    private val nAlong: Int = 4,
    private val nDip: Int = 4,
    private val nu: Double = Conventions.POISSON_RATIO,
) : DeformationModel {
    private val mogi = MogiModel(nu)

    override fun displacement(site: Site, p: SourceParams): Vec3 {
        val ori = PlaneOrientation(p.strike, p.dip)
        val halfL = p.length / 2.0
        val halfW = p.width / 2.0
        var acc = Vec3(0.0, 0.0, 0.0)
        val cellArea = (p.length / nAlong) * (p.width / nDip) // km^2
        // 将总开口量均匀分配到各子点源： dV_cell = open * cellArea（单位换算到 m^3）
        val dvCell = p.strength * cellArea * 1.0e6 / (nAlong * nDip)
        for (i in 0 until nAlong) {
            val s = -halfL + (i + 0.5) * p.length / nAlong
            for (j in 0 until nDip) {
                val t = -halfW + (j + 0.5) * p.width / nDip
                val ex = ori.alongStrike.x * s + ori.downDip.x * t
                val ey = ori.alongStrike.y * s + ori.downDip.y * t
                val ez = ori.alongStrike.z * s + ori.downDip.z * t // ENU Up；下倾为负
                val sub = SourceParams(
                    p.x + ex, p.y + ey, p.depth - ez,
                    p.strike, p.dip, p.length, p.width, dvCell,
                )
                acc += mogi.displacement(site, sub)
            }
        }
        return acc
    }
}
