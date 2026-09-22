package app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 全局坐标约定（数据口径，README 与导出记录均引用本版本号）：
 *
 *  - 水平坐标：局部切平面东-北（ENU），单位 km；x=Easting, y=Northing。
 *    数据导入时保留源坐标系标识（crs），投影计算一律在 ENU 中进行；
 *    坐标变换链由 GeoTransform 记录版本号以便溯源。
 *  - 垂直坐标：位移与“高程” u_z 向上为正（ENU 的 Up）。
 *  - 深度参数：所有源参数 depth 以“地表向下为正”（depth > 0 表示源在地下），
 *    与高程（向上为正）严格分离，不混用符号口径。
 *  - 视线 LOS：单位向量 (lE, lN, lU) 沿“传感器 -> 目标点”方向；
 *    预测的标量视线位移 = l·u（位移沿卫星视线，远离传感器为正，与常见 InSAR
 *    “增大斜距/LOS 远离”的约定一致；若数据厂商使用相反约定，应在导入阶段
 *    翻转观测值符号而非在此处改公式）。
 */
object Conventions {
    const val COORD_VERSION = "ENU-LOCAL-v1"
    const val LOS_TOLERANCE = 1.0e-6
    const val DEPTH_SIGN = "depth positive downward (subsurface > 0); elevation/Up positive upward"
    const val POISSON_RATIO = 0.25
    const val ENGINE_VERSION = "volcano-studio-engine-1.0.0"
}

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun norm() = kotlin.math.sqrt(dot(this))
}

/** 观测点：ENU 坐标（km）。 */
data class Site(val id: String, val x: Double, val y: Double)

/**
 * 视线单位向量。导入时强制 |l| = 1（容差 Conventions.LOS_TOLERANCE）；
 * GNSS 三分量使用固定基向量 (1,0,0)/(0,1,0)/(0,0,1)。
 */
data class LosVec(val e: Double, val n: Double, val u: Double) {
    val norm: Double get() = kotlin.math.sqrt(e * e + n * n + u * u)
    val deviation: Double get() = kotlin.math.abs(norm - 1.0)
    fun asVec3() = Vec3(e, n, u)
}

/** 坐标变换链溯源：从源 CRS 到 ENU 的变换版本与参考原点。 */
data class GeoTransform(
    val sourceCrs: String,
    val frame: String = "ENU local tangent plane",
    val originLat: Double? = null,
    val originLon: Double? = null,
    val version: String = Conventions.COORD_VERSION,
)

/**
 * 走向/倾角约定：
 *  strike：走向角，自正北顺时针（地理方位角，0..360 度）；
 *  dip：与水平面夹角（0=水平岩床 sill，90=直立岩墙）。
 * 返回 (沿走向单位向量, 沿倾向下倾单位向量, 法向量)，均在 ENU 中。
 */
data class PlaneOrientation(val strikeDeg: Double, val dipDeg: Double) {
    val s: Double = Math.toRadians(strikeDeg)
    val d: Double = Math.toRadians(dipDeg)

    /** 沿走向（方位角 strike）。 */
    val alongStrike: Vec3 = Vec3(sin(s), cos(s), 0.0)

    /** 沿平面向下（下倾方向）：水平投影方位角 strike+90，z 分量向下为负(ENU)。 */
    val downDip: Vec3 = Vec3(sin(s + PI / 2) * cos(d), cos(s + PI / 2) * cos(d), -sin(d))

    /** 平面法向：alongStrike × downDip（直立岩墙时水平、指向下盘前方）。 */
    val normal: Vec3 = run {
        val a = alongStrike
        val b = downDip
        Vec3(
            a.y * b.z - a.z * b.y,
            a.z * b.x - a.x * b.z,
            a.x * b.y - a.y * b.x,
        )
    }
}
