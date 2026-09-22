package app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class ConventionsTest {

    @Test
    fun `insar LOS must be unit length within tolerance`() {
        val good = Observation(
            id = "x", siteId = "s", type = "insar_los", x = 0.0, y = 0.0,
            value = 0.01, sigmaWhite = 0.005, groupId = "g", epoch = "e",
            losE = -0.462, losN = -0.117, losU = 0.879,
        )
        // 这组向量并非严格归一，偏差约为 0.0027（演示导入器会生成精确单位向量）
        val dev = LosVec(-0.462, -0.117, 0.879).deviation
        assertTrue(dev > Conventions.LOS_TOLERANCE)

        val normalized = let {
            val m = sqrt(0.462 * 0.462 + 0.117 * 0.117 + 0.879 * 0.879)
            good.copy(losE = -0.462 / m, losN = -0.117 / m, losU = 0.879 / m)
        }
        requireLosNormalized(listOf(normalized))

        val bad = Observation(
            id = "b", siteId = "s", type = "insar_los", x = 0.0, y = 0.0,
            value = 0.01, sigmaWhite = 0.005, groupId = "g", epoch = "e",
            losE = 0.5, losN = 0.5, losU = 0.5,
        )
        assertThrows(LosNormalizationException::class.java) { requireLosNormalized(listOf(bad)) }
    }

    @Test
    fun `depth positive downward while up displacement positive upward`() {
        // 口径检查：深度>0（地下），膨胀 Mogi 在正上方产生 u_z>0（向上抬升）
        val p = SourceParams(0.0, 0.0, 5.0, 0.0, 90.0, 0.0, 0.0, 1.0e7)
        val u = MogiModel().displacement(Site("c", 0.0, 0.0), p)
        assertTrue(p.depth > 0)
        assertTrue(u.z > 0) { "膨胀源在正上方应抬升，u_z=${u.z}" }
        assertEquals(0.0, u.x, 1e-12); assertEquals(0.0, u.y, 1e-12)
    }
}
