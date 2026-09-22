package app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Okada (1985/1992) 有限矩形位错面在弹性半空间自由表面产生的位移。
 *
 * 本实现移植自 Okada 官方 DC3D 程序（BSSA 1985 公式；pyrocko okada_ext 的
 * C 语言转写用于交叉核对）。自由表面 z=0 时位移仅由像源支（DC3D 的 part-b）
 * 贡献，因此这里只需 dccon0 / dccon2 / ub 三段。
 *
 * 内部坐标（Okada 原始口径）：X 沿走向、Y 在水平面内指向“上盘前方”，
 * 深度 D 向下为正、倾角 delta 自水平面向下，
 *   p = Y cosδ + D sinδ（沿倾向，自上沿向下）
 *   q = Y sinδ − D cosδ（面法向，指向下方盘）
 * 位移分量 ux,uy,uz 在内部系中为 (沿走向, 沿水平 Y, 竖直向上)，
 * 再旋转回 ENU。
 */

private const val EPS = 1.0e-6

private class C0(
    val alp1: Double, val alp2: Double, val alp3: Double,
    val alp4: Double, val alp5: Double,
    val sd: Double, val cd: Double,
    val sdsd: Double, val cdcd: Double, val sdcd: Double,
)

private class C2 {
    var xi2 = 0.0; var et2 = 0.0; var q2 = 0.0
    var r2 = 0.0; var r = 0.0; var r3 = 0.0; var r5 = 0.0
    var y = 0.0; var d = 0.0
    var tt = 0.0
    var alx = 0.0; var x11 = 0.0; var x32 = 0.0
    var ale = 0.0; var y11 = 0.0; var y32 = 0.0
    var ey = 0.0; var ez = 0.0; var fy = 0.0; var fz = 0.0
    var gy = 0.0; var gz = 0.0; var hy = 0.0; var hz = 0.0
}

private fun dccon0(alpha: Double, dipRad: Double): C0 {
    var sd = sin(dipRad)
    var cd = cos(dipRad)
    if (abs(cd) < EPS) {
        cd = 0.0
        sd = if (sd > 0) 1.0 else if (sd < 0) -1.0 else sd
    }
    return C0(
        alp1 = (1.0 - alpha) / 2.0,
        alp2 = alpha / 2.0,
        alp3 = (1.0 - alpha) / alpha,
        alp4 = 1.0 - alpha,
        alp5 = alpha,
        sd = sd, cd = cd,
        sdsd = sd * sd, cdcd = cd * cd, sdcd = sd * cd,
    )
}

private fun dccon2(xiIn: Double, etIn: Double, qIn: Double, c0: C0): C2? {
    var xi = xiIn; var et = etIn; var q = qIn
    if (abs(xi) < EPS) xi = 0.0
    if (abs(et) < EPS) et = 0.0
    if (abs(q) < EPS) q = 0.0
    val c = C2()
    c.xi2 = xi * xi; c.et2 = et * et; c.q2 = q * q
    c.r2 = c.xi2 + c.et2 + c.q2
    c.r = sqrt(c.r2)
    if (c.r == 0.0) return null
    c.r3 = c.r * c.r2
    c.r5 = c.r3 * c.r2
    c.y = et * c0.cd + q * c0.sd
    c.d = et * c0.sd - q * c0.cd
    c.tt = if (q == 0.0) 0.0 else atan(xi * et / (q * c.r))

    val kxi = (xi < 0.0 && c.r + xi < EPS)
    if (kxi) {
        c.alx = -ln(c.r - xi); c.x11 = 0.0; c.x32 = 0.0
    } else {
        val rxi = c.r + xi
        c.alx = ln(rxi)
        c.x11 = 1.0 / (c.r * rxi)
        c.x32 = (c.r + rxi) * c.x11 * c.x11 / c.r
    }
    val ket = (et < 0.0 && c.r + et < EPS)
    if (ket) {
        c.ale = -ln(c.r - et); c.y11 = 0.0; c.y32 = 0.0
    } else {
        val ret = c.r + et
        c.ale = ln(ret)
        c.y11 = 1.0 / (c.r * ret)
        c.y32 = (c.r + ret) * c.y11 * c.y11 / c.r
    }

    c.ey = c0.sd / c.r - c.y * q / c.r3
    c.ez = c0.cd / c.r + c.d * q / c.r3
    c.fy = c.d / c.r3 + c.xi2 * c.y32 * c0.sd
    c.fz = c.y / c.r3 + c.xi2 * c.y32 * c0.cd
    c.gy = 2.0 * c.x11 * c0.sd - c.y * q * c.x32
    c.gz = 2.0 * c.x11 * c0.cd + c.d * q * c.x32
    c.hy = c.d * q * c.x32 + xi * q * c.y32 * c0.sd
    c.hz = c.y * q * c.x32 + xi * q * c.y32 * c0.cd
    return c
}

/** part-b（像源支）位移，数组顺序与 Okada DC3D 的 u[0..2] 一致。 */
private fun ubDisplacement(
    xiIn: Double, etIn: Double, qIn: Double,
    disl1: Double, disl2: Double, disl3: Double,
    c0: C0,
): DoubleArray? {
    val c2 = dccon2(xiIn, etIn, qIn, c0) ?: return null
    var xi = xiIn; var et = etIn; var q = qIn
    if (abs(xi) < EPS) xi = 0.0
    if (abs(et) < EPS) et = 0.0
    if (abs(q) < EPS) q = 0.0

    val rd = c2.r + c2.d
    val d11 = 1.0 / (c2.r * rd)
    var ai1: Double; var ai2: Double; var ai3: Double; var ai4: Double
    var ak1: Double; var ak2: Double; var ak3: Double; var ak4: Double
    val aj2 = xi * c2.y / rd * d11
    val aj5 = -(c2.d + c2.y * c2.y / rd) * d11
    var aj3: Double; var aj6: Double
    if (c0.cd != 0.0) {
        ai4 = if (xi == 0.0) 0.0 else {
            val x = sqrt(c2.xi2 + c2.q2)
            1.0 / c0.cdcd * (
                xi / rd * c0.sdcd + 2.0 * atan(
                    (et * (x + q * c0.cd) + x * (c2.r + x) * c0.sd) /
                        (xi * (c2.r + x) * c0.cd),
                ))
        }
        ai3 = (c2.y * c0.cd / rd - c2.ale + c0.sd * ln(rd)) / c0.cdcd
        ak1 = xi * (d11 - c2.y11 * c0.sd) / c0.cd
        ak3 = (q * c2.y11 - c2.y * d11) / c0.cd
        aj3 = (ak1 - aj2 * c0.sd) / c0.cd
        aj6 = (ak3 - aj5 * c0.sd) / c0.cd
    } else {
        val rd2 = rd * rd
        ai3 = (et / rd + c2.y * q / rd2 - c2.ale) / 2.0
        ai4 = xi * c2.y / rd2 / 2.0
        ak1 = xi * q / rd * d11
        ak3 = c0.sd / rd * (c2.xi2 * d11 - 1.0)
        aj3 = -xi / rd2 * (c2.q2 * d11 - 0.5)
        aj6 = -c2.y / rd2 * (c2.xi2 * d11 - 0.5)
    }
    val xy = xi * c2.y11
    ai1 = -xi / rd * c0.cd - ai4 * c0.sd
    ai2 = ln(rd) + ai3 * c0.sd
    ak2 = 1.0 / c2.r + ak3 * c0.sd
    ak4 = xy * c0.cd - ak1 * c0.sd
    val aj1 = aj5 * c0.cd - aj6 * c0.sd
    val aj4 = -xy - aj2 * c0.cd + aj3 * c0.sd

    val qx = q * c2.x11
    val qy = q * c2.y11
    val u = DoubleArray(3)

    if (disl1 != 0.0) {
        val du = doubleArrayOf(
            -xi * qy - c2.tt - c0.alp3 * ai1 * c0.sd,
            -q / c2.r + c0.alp3 * c2.y / rd * c0.sd,
            q * qy - c0.alp3 * ai2 * c0.sd,
        )
        val f = disl1 / (2.0 * PI)
        for (i in 0..2) u[i] += f * du[i]
    }
    if (disl2 != 0.0) {
        val du = doubleArrayOf(
            -q / c2.r + c0.alp3 * ai3 * c0.sdcd,
            -et * qx - c2.tt - c0.alp3 * xi / rd * c0.sdcd,
            q * qx + c0.alp3 * ai4 * c0.sdcd,
        )
        val f = disl2 / (2.0 * PI)
        for (i in 0..2) u[i] += f * du[i]
    }
    if (disl3 != 0.0) {
        val du = doubleArrayOf(
            q * qy - c0.alp3 * ai3 * c0.sdsd,
            q * qx + c0.alp3 * xi / rd * c0.sdsd,
            et * qx + xi * qy - c2.tt - c0.alp3 * ai4 * c0.sdsd,
        )
        val f = disl3 / (2.0 * PI)
        for (i in 0..2) u[i] += f * du[i]
    }
    return u
}

/**
 * 岩墙（张性矩形断裂）模型。
 *
 * 参数中心口径：(x,y) 为矩形中心点的水平位置，depth 为中心点的地下深度
 * （向下为正）；length 沿走向全长，width 沿倾向全宽。
 * Okada DC3D 的参考点是矩形“上沿中点”，故 D_top = depth - width/2 * sin(dip)。
 *
 * 开口量 open>0 表示张裂（上盘沿 +q 法向运动），对应抬升型形变；
 * 内部计算单位为米（坐标乘 1000）。
 */
class DikeModel(
    private val nu: Double = Conventions.POISSON_RATIO,
) : DeformationModel {

    // alpha = (lambda+mu)/(lambda+2mu) = 1/(2(1-nu))
    private val alpha = 1.0 / (2.0 * (1.0 - nu))

    fun topDepth(p: SourceParams): Double =
        p.depth - 0.5 * p.width * sin(Math.toRadians(p.dip))

    /** 上沿是否在地表以下（物理可行域）。 */
    fun isFeasible(p: SourceParams): Boolean = topDepth(p) >= 1.0e-4

    override fun displacement(site: Site, p: SourceParams): Vec3 {
        // 上沿穿出地表属非物理参数；正演返回零场，由优化器按软惩罚处理。
        if (!isFeasible(p)) return Vec3(0.0, 0.0, 0.0)
        // ENU 中源->观测点向量（m）
        val dxE = (site.x - p.x) * 1000.0
        val dyN = (site.y - p.y) * 1000.0

        // 走向方位角（自北顺时针）。Okada 内部 X 沿走向，Y 水平指向盘前方。
        val s = Math.toRadians(p.strike)
        // ENU 差向量 -> 内部 (X, Y)（已与官方 DC3D 数值核对）：
        val xiC = dxE * sin(s) + dyN * cos(s)
        val yInt = -dxE * cos(s) + dyN * sin(s)

        val dipRad = Math.toRadians(p.dip)
        val c0 = dccon0(alpha, dipRad)
        val halfL = p.length * 1000.0 / 2.0
        val w = p.width * 1000.0
        val dTop = p.depth * 1000.0

        var acc = DoubleArray(3)
        var singular = false
        // Chinnery 求和（与 Okada DC3D 一致：j==k 取正，否则取负）
        for (k in 0..1) {
            for (j in 0..1) {
                val al = if (j == 0) -halfL else halfL
                val aw = if (k == 0) 0.0 else w
                val xi = xiC - al
                val pp = yInt * c0.cd + dTop * c0.sd
                val qq = yInt * c0.sd - dTop * c0.cd
                val et = pp - aw
                val u = ubDisplacement(xi, et, qq, 0.0, 0.0, p.strength, c0)
                if (u == null) {
                    singular = true
                    continue
                }
                // 像源支位移从 (沿走向, y, d) 盘坐标旋回 (沿走向, 水平Y, 竖直Up)
                // （对应官方 DC3D 中 dub 的 cd/sd 旋转）
                val uxS = u[0]
                val uyH = u[1] * c0.cd - u[2] * c0.sd
                val uzV = u[1] * c0.sd + u[2] * c0.cd
                val sign = if (j == k) 1.0 else -1.0
                acc[0] += sign * uxS
                acc[1] += sign * uyH
                acc[2] += sign * uzV
            }
        }
        if (singular) {
            // 观测点恰好落在断裂边缘（数学奇点）；做一个亚毫米级偏移兜底。
            return displacement(Site(site.id, site.x + 1.0e-4, site.y + 1.0e-4), p)
        }

        // 内部系 (X 沿走向, Y 盘前水平, Up) -> ENU（前向映射的逆）：
        //   X = dxE*sin + dyN*cos ; Y = -dxE*cos + dyN*sin
        //   => dxE = X*sin - Y*cos ; dyN = X*cos + Y*sin
        // DC3D part-b 输出顺序：u[0]=沿走向 X, u[1]=盘前水平 Y, u[2]=竖直向上
        val uX = acc[0]
        val uY = acc[1]
        val uUp = acc[2]
        // 前向: X = dxE*sin + dyN*cos ; Y = -dxE*cos + dyN*sin
        // 逆: dxE = X*sin - Y*cos ; dyN = X*cos + Y*sin
        val ue = uX * sin(s) - uY * cos(s)
        val un = uX * cos(s) + uY * sin(s)
        return Vec3(ue, un, uUp)
    }
}
