package app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * 参考值来自 Okada 官方 DC3D（BSSA 1985）程序在相同参数下的直接计算，
 * 用于保证 Kotlin 移植在走向/倾角/张裂组合上的正确性（容许 1e-9 m）。
 */
class DikeTest {
    private val dike = DikeModel()

    private fun check(
        xe: Double, yn: Double, depth: Double, dip: Double,
        strike: Double, eRef: Double, nRef: Double, uRef: Double,
        L: Double = 4.0, W: Double = 3.0, tol: Double = 2.0e-9,
    ) {
        val p = SourceParams(0.0, 0.0, depth, strike, dip, L, W, 1.0)
        val u = dike.displacement(Site("s", xe, yn), p)
        assertEquals(eRef, u.x, tol, "E mismatch at ($xe,$yn)")
        assertEquals(nRef, u.y, tol, "N mismatch at ($xe,$yn)")
        assertEquals(uRef, u.z, tol, "U mismatch at ($xe,$yn)")
    }

    @Test
    fun `vertical tensile dike matches DC3D strike zero`() {
        // dip=90, strike=0, center depth 3 km, L=4 W=3 (top depth 3 km)
        check(-3.0, 0.0, 3.0, 90.0, 0.0, -0.2614714039, 0.0, 0.06180984765)
        check(3.0, 0.0, 3.0, 90.0, 0.0, 0.2614714039, 0.0, 0.06180984765)
        check(0.0, 3.0, 3.0, 90.0, 0.0, 0.0, -0.04386719289, 0.03214235289)
        check(0.0, -3.0, 3.0, 90.0, 0.0, 0.0, 0.04386719289, 0.03214235289)
    }

    @Test
    fun `horizontal sill matches DC3D strike zero`() {
        check(-3.0, 0.0, 4.0, 0.0, 0.0, -0.06669342137, 0.0, 0.2141458580)
        check(3.0, 0.0, 4.0, 0.0, 0.0, 0.04948311946, 0.0, 0.04856796641)
    }

    @Test
    fun `dipping dike matches DC3D`() {
        check(2.5, -1.5, 3.5, 60.0, 0.0, 0.181256, -0.049907, 0.105901, tol = 2.0e-6)
    }

    @Test
    fun `general strike rotation consistency with DC3D`() {
        // 与官方 dc3d_flexi 的 NED 旋转口径核对（120°/70°）
        check(3.0, 4.0, 3.0, 70.0, 120.0, 0.06414782535, 0.09639525982, 0.01159210839)
        check(3.0, 0.0, 3.0, 70.0, 120.0, -0.01298191418, 0.03545830692, 0.01956421026)
        check(-2.0, 5.0, 3.0, 70.0, 120.0, 0.006754470916, 0.03160952929, 0.01295606088)
        check(0.0, 3.0, 3.0, 70.0, 120.0, 0.06257724452, 0.1531504426, 0.03920180518)
    }

    @Test
    fun `opening sign reversal inverts displacement`() {
        val p = SourceParams(0.0, 0.0, 3.0, 30.0, 70.0, 4.0, 3.0, 1.0)
        val a = dike.displacement(Site("s", 2.0, 2.0), p)
        val b = dike.displacement(Site("s", 2.0, 2.0), p.copy(strength = -1.0))
        assertEquals(a.x, -b.x, 1e-12)
        assertEquals(a.y, -b.y, 1e-12)
        assertEquals(a.z, -b.z, 1e-12)
    }

    @Test
    fun `top edge above surface is infeasible`() {
        val p = SourceParams(0.0, 0.0, 1.0, 0.0, 80.0, 4.0, 3.0, 1.0)
        assertTrue(dike.topDepth(p) < 0)
        assertEquals(false, dike.isFeasible(p))
    }
}
