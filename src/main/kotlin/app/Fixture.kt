package app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 固定演示数据集（种子固定、可重放）：
 *  - 真实源为 Mogi 点源（膨胀），GNSS 两个时段 + 两条 InSAR 轨道；
 *  - 西南象限设置“地表缺测”扇区，制造深度/几何的非唯一性；
 *  - 噪声严格按协方差结构生成：white_i*z_i + sigmaCme_g*z_g
 *    （同一组共享同一 z_g，体现真实相关；反演不重复抽样）。
 *  - 区域：距源 4 km 内为 near，其余为 far。
 */
object Fixture {

    const val SEED = 20240517L
    val truthMogi = SourceParams(
        x = 0.2, y = -0.3, depth = 6.5,
        strike = 0.0, dip = 90.0, length = 0.0, width = 0.0,
        strength = 2.5e7, // dV m^3
    )

    private val losA84 = unitLos(-0.462, -0.117, 0.879) // 升轨
    private val losD35 = unitLos(0.403, 0.092, 0.910)   // 降轨

    private fun unitLos(e: Double, n: Double, u: Double): Triple<Double, Double, Double> {
        val m = sqrt(e * e + n * n + u * u)
        return Triple(e / m, n / m, u / m)
    }

    fun bundle(): ImportBundle {
        val rng = GaussianRng(Random(SEED))
        val obs = mutableListOf<Observation>()
        val groups = listOf(
            CovarianceGroup("G-E1", "GNSS 第一时段 2024-05", "gnss", "2024-05", 0.006),
            CovarianceGroup("G-E2", "GNSS 第二时段 2024-07", "gnss", "2024-07", 0.005),
            CovarianceGroup("T-A84", "InSAR 升轨 A84", "insar", "2024-05~07", 0.012),
            CovarianceGroup("T-D35", "InSAR 降轨 D35", "insar", "2024-05~07", 0.010),
        )
        val cmeDraw = groups.associateWith { rng.nextGaussian() }

        val model = MogiModel()
        fun region(x: Double, y: Double): String =
            if (sqrt((x - truthMogi.x) * (x - truthMogi.x) + (y - truthMogi.y) * (y - truthMogi.y)) <= 4.0)
                "near" else "far"

        // 地表缺测：西南象限（x<0 且 y<0）不布设任何观测
        fun gap(x: Double, y: Double) = x < -0.5 && y < -0.5

        var n = 0
        // GNSS：两圈 12 站，两个时段（部分站第二时段缺测，体现观测时段差异）
        val stations = mutableListOf<Pair<Double, Double>>()
        for (ring in 0..1) {
            val radius = if (ring == 0) 3.2 else 6.4
            val count = if (ring == 0) 6 else 8
            for (k in 0 until count) {
                val ang = 2.0 * PI * k / count + ring * 0.35
                val x = truthMogi.x + radius * cos(ang)
                val y = truthMogi.y + radius * sin(ang)
                if (gap(x, y)) continue
                stations.add(x to y)
            }
        }
        stations.forEachIndexed { i, (x, y) ->
            val u = model.displacement(Site("S%02d".format(i + 1), x, y), truthMogi)
            val site = "S%02d".format(i + 1)
            val epochs = if (i % 3 == 0) listOf("G-E1") else listOf("G-E1", "G-E2")
            for (gid in epochs) {
                val g = groups.first { it.id == gid }
                for ((comp, proj, code) in listOf(
                    Triple(u.x, Vec3(1.0, 0.0, 0.0), "gnss_e"),
                    Triple(u.y, Vec3(0.0, 1.0, 0.0), "gnss_n"),
                    Triple(u.z, Vec3(0.0, 0.0, 1.0), "gnss_u"),
                )) {
                    n++
                    val white = 0.003
                    val v = comp + white * rng.nextGaussian() +
                        g.sigmaCme * cmeDraw.getValue(g)
                    obs.add(
                        Observation(
                            id = "$site-${if (gid == "G-E1") "E1" else "E2"}-${code.takeLast(1)}",
                            siteId = site, type = code, x = x, y = y, value = v,
                            sigmaWhite = white, groupId = gid, epoch = g.epoch,
                            region = region(x, y),
                        ),
                    )
                }
            }
        }

        // InSAR：近规则网格两轨，西南象限缺测
        val grid = mutableListOf<Pair<Double, Double>>()
        var k = 0
        for (ix in -5..5) for (iy in -5..5) {
            val x = ix * 1.35
            val y = iy * 1.35
            if (gap(x, y)) continue
            if (sqrt((x - truthMogi.x).pow2() + (y - truthMogi.y).pow2()) > 7.5) continue
            k++
            if (k % 2 == 0) grid.add(x to y)
        }
        grid.forEachIndexed { i, (x, y) ->
            val u = model.displacement(Site("I$i", x, y), truthMogi)
            for ((gid, los) in listOf("T-A84" to losA84, "T-D35" to losD35)) {
                if ((gid == "T-D35") && i % 4 == 1) return@forEachIndexed // 额外缺测，几何不齐
                val g = groups.first { it.id == gid }
                val (le, ln, lu) = los
                val white = 0.005
                val v = (le * u.x + ln * u.y + lu * u.z) +
                    white * rng.nextGaussian() + g.sigmaCme * cmeDraw.getValue(g)
                obs.add(
                    Observation(
                        id = "$gid-P%03d".format(i), siteId = "I$i",
                        type = "insar_los", x = x, y = y, value = v,
                        sigmaWhite = white, groupId = gid, epoch = g.epoch,
                        losE = le, losN = ln, losU = lu, region = region(x, y),
                    ),
                )
            }
        }

        val ds = Dataset(
            name = "演示火山 A（固定 fixture）",
            crs = "EPSG:32654 -> ENU local",
            transformVersion = Conventions.COORD_VERSION,
            originLat = 35.40, originLon = 138.70,
            importedAt = "",
            sourceHash = "",
            note = "Mogi 真值 dV=2.5e7 m3, depth=6.5 km；西南象限缺测；含分组共同模式误差",
        )
        val rendered = CsvImport.render(ImportBundle(ds, obs, groups))
        return ImportBundle(ds.copy(sourceHash = sha256(rendered)), obs, groups)
    }

    private fun Double.pow2() = this * this
}

/** Box-Muller 高斯随机数；随实例固定种子 => 噪声序列可重放。 */
class GaussianRng(private val rng: Random) {
    private var spare: Double? = null
    fun nextGaussian(): Double {
        spare?.let { spare = null; return it }
        var u = 0.0; var v = 0.0; var s = 0.0
        while (s <= 1e-12 || s >= 1.0) {
            u = rng.nextDouble() * 2 - 1
            v = rng.nextDouble() * 2 - 1
            s = u * u + v * v
        }
        val mul = sqrt(-2.0 * kotlin.math.ln(s) / s)
        spare = v * mul
        return u * mul
    }
}
