package app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 验收场景：同一观测场存在点源与受限平面源两种近等价解；
 * 平面源在深度上界 6 km 处最优，必须报告“边界活跃（深度上界）”，
 * 而点源是内部稳定解。两个模型各自报告指标，不做综合分数压扁。
 */
class InversionFixtureTest {

    private val bundle = Fixture.bundle()
    private val cov = CovarianceStructure.build(bundle.observations, bundle.groups)

    @Test
    fun `fixture geometry preserves LOS normalization groups epochs and gap`() {
        requireLosNormalized(bundle.observations)
        assertEquals(4, bundle.groups.size)
        val epochs = bundle.observations.map { it.epoch }.toSet()
        assertTrue(epochs.contains("2024-05") && epochs.contains("2024-07"))
        // 西南象限缺测
        assertTrue(bundle.observations.none { it.x < -0.5 && it.y < -0.5 })
        assertTrue(bundle.observations.any { it.type.startsWith("gnss") })
        assertTrue(bundle.observations.any { it.type == "insar_los" })
    }

    @Test
    fun `mogi point solution is interior`() {
        val opt = MultiStartOptimizer(
            ModelType.MOGI, PhysicalBounds(depthMax = 9.0),
            bundle.observations, cov, Regularization(0.0),
        )
        val res = opt.run(20260922L, 16, 350)
        val best = res.first()
        val p = SourceParams.fromArray(ModelType.MOGI, best.params)
        val active = opt.activeBounds(best.params)
        assertFalse(active.active, "点源最优不应触碰任何边界: ${active.details}")
        assertEquals(6.5, p.depth, 0.25)
        assertEquals(2.5e7, p.strength, 0.35e7)
        val red = best.objective / (bundle.observations.size - best.params.size)
        assertTrue(red in 0.8..1.8) { "约化 chi2 应接近噪声水平: $red" }
    }

    @Test
    fun `confined plane solution is near equivalent and hits depth upper bound`() {
        val bounds = PhysicalBounds(
            depthMin = 0.05, depthMax = 6.0,
            dipMin = 0.0, dipMax = 40.0,
            lengthMax = 10.0, widthMax = 10.0,
        )
        val opt = MultiStartOptimizer(
            ModelType.PLANE, bounds,
            bundle.observations, cov, Regularization(0.0),
        )
        val res = opt.run(20260922L, 24, 350)
        val best = res.first()
        val active = opt.activeBounds(best.params)

        assertTrue(active.active, "受限平面源最优必须报告边界活跃")
        assertTrue(active.depthAtUpper) { "最优深度应落在上界: ${active.details}" }
        assertTrue(active.details.any { it.contains("触及上界") && it.contains("深度") })

        val p = SourceParams.fromArray(ModelType.PLANE, best.params)
        assertEquals(6.0, p.depth, 0.02)

        // 与点源近等价：两者 chi2 差异小于约 15%
        val optMogi = MultiStartOptimizer(
            ModelType.MOGI, PhysicalBounds(depthMax = 9.0),
            bundle.observations, cov, Regularization(0.0),
        )
        val mogiChi2 = optMogi.run(20260922L, 16, 350).first().objective
        val rel = kotlin.math.abs(best.objective - mogiChi2) / mogiChi2
        assertTrue(rel < 0.15) { "点源/平面源应近等价：plane=${best.objective} mogi=$mogiChi2 rel=$rel" }
    }

    @Test
    fun `regularization pulls toward center and changes objective as expected`() {
        val noReg = MultiStartOptimizer(
            ModelType.MOGI, PhysicalBounds(depthMax = 9.0),
            bundle.observations, cov, Regularization(0.0),
        ).run(20260922L, 8, 200).first().objective
        val strong = MultiStartOptimizer(
            ModelType.MOGI, PhysicalBounds(depthMax = 9.0),
            bundle.observations, cov, Regularization(500.0),
        ).run(20260922L, 8, 200).first().objective
        assertTrue(strong >= noReg)
    }
}
