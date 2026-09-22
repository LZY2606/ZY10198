package app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class MogiTest {
    @Test
    fun `radial symmetry and antisymmetry`() {
        val m = MogiModel()
        val p = SourceParams(1.0, -1.0, 5.5, 0.0, 90.0, 0.0, 0.0, 1.2e7)
        val u = m.displacement(Site("a", 5.0, -1.0), p)
        val w = m.displacement(Site("b", -3.0, -1.0), p)
        // 关于源点径向对称的水平场
        val n = m.displacement(Site("c", 1.0, 3.0), p)
        assertEquals(0.0, n.x, 1e-12)
        // 体积变化符号反转 -> 位移反转
        val p2 = p.copy(strength = -1.2e7)
        val u2 = m.displacement(Site("a", 5.0, -1.0), p2)
        assertEquals(-u.x, u2.x, 1e-12)
        assertEquals(-u.z, u2.z, 1e-12)
    }
}
