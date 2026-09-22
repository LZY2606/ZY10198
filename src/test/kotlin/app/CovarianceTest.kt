package app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp

class CovarianceTest {

    private fun obs(i: Int, sigma: Double, group: String) = Observation(
        id = "o$i", siteId = "s$i", type = "gnss_e", x = i.toDouble(), y = 0.0,
        value = 0.0, sigmaWhite = sigma, groupId = group, epoch = "e",
    )

    @Test
    fun `common mode adds shared covariance not repeated independent samples`() {
        // 3 条同组观测：白噪声 0.004，CME 0.01；C = D + s s^T
        val list = (0 until 3).map { obs(it, 0.004, "g1") }
        val groups = listOf(CovarianceGroup("g1", "g", "gnss", "e", 0.01))
        val cov = CovarianceStructure.build(list, groups)

        // C^{-1}1 与手工构造稠密矩阵的二次型一致
        val dense = Array(3) { i ->
            DoubleArray(3) { j ->
                (if (i == j) 0.004 * 0.004 else 0.0) + 0.01 * 0.01
            }
        }
        val r = doubleArrayOf(0.01, -0.02, 0.015)
        val expected = DoubleArray(3)
        for (i in 0 until 3) for (j in 0 until 3) expected[i] += inverse(dense)[i][j] * r[j]
        var q = 0.0
        for (i in 0 until 3) q += r[i] * expected[i]
        assertEquals(q, cov.quadratic(r), 1e-10)
    }

    @Test
    fun `larger common mode reduces effective information toward shared direction`() {
        // CME 越强，共同（平均）模式越难约束：残差全相同时二次型应显著变小
        val list = (0 until 8).map { obs(it, 0.005, "g1") }
        val r = DoubleArray(8) { 0.02 }
        val noCme = CovarianceStructure.build(list, listOf(CovarianceGroup("g1", "g", "gnss", "e", 0.0)))
        val bigCme = CovarianceStructure.build(list, listOf(CovarianceGroup("g1", "g", "gnss", "e", 0.03)))
        assertTrue(bigCme.quadratic(r) < noCme.quadratic(r) * 0.2) {
            "共同模式误差应吸收共享残差：${bigCme.quadratic(r)} vs ${noCme.quadratic(r)}"
        }
        // 而正交（零和）残差模式不受 CME 影响
        val orth = DoubleArray(8) { if (it % 2 == 0) 0.02 else -0.02 }
        assertEquals(noCme.quadratic(orth), bigCme.quadratic(orth), 1e-8)
    }

    @Test
    fun `groups are independent across one another`() {
        val list = (0 until 4).map { obs(it, 0.005, if (it < 2) "a" else "b") }
        val groups = listOf(
            CovarianceGroup("a", "a", "gnss", "e", 0.02),
            CovarianceGroup("b", "b", "gnss", "e", 0.02),
        )
        val cov = CovarianceStructure.build(list, groups)
        val dense = Array(4) { i ->
            DoubleArray(4) { j ->
                val base = if (i == j) 0.005 * 0.005 else 0.0
                base + if ((i < 2) == (j < 2)) 0.02 * 0.02 else 0.0
            }
        }
        val r = doubleArrayOf(0.01, 0.01, -0.01, -0.01)
        var q = 0.0
        val inv = inverse(dense)
        for (i in 0 until 4) for (j in 0 until 4) q += r[i] * inv[i][j] * r[j]
        assertEquals(q, cov.quadratic(r), 1e-10)
    }

    private fun inverse(a: Array<DoubleArray>): Array<DoubleArray> = invertSymmetric(a)
}
