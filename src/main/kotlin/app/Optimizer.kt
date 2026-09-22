package app

import kotlinx.serialization.Serializable

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

data class NMResult(
    val z: DoubleArray,
    val f: Double,
    val iterations: Int,
    val converged: Boolean,
)

data class StartResult(
    val startIndex: Int,
    val params: DoubleArray,
    val objective: Double,
    val iterations: Int,
    val converged: Boolean,
)

@Serializable
data class ActiveBounds(
    val active: Boolean,
    val depthAtUpper: Boolean,
    val depthAtLower: Boolean,
    val sizeAtUpper: List<String>,
    val details: List<String>,
)

/**
 * 多起点有界反演。
 *  - 目标函数：chi2(p) = r^T C^{-1} r + 正则项
 *  - 每个起点做有界 Nelder–Mead（参数归一化到 [0,1]，边界用反射裁剪）
 *  - 起点集合由固定种子的分层网格 + 确定性 LCG 微扰构成，保证可重放
 */
class MultiStartOptimizer(
    private val type: ModelType,
    private val bounds: PhysicalBounds,
    private val observations: List<app.Observation>,
    private val covariance: CovarianceStructure,
    private val regularization: Regularization,
    private val dataVector: DoubleArray = DoubleArray(observations.size) { observations[it].value },
) {
    private val engine = ForwardEngine(type)
    private val specs = bounds.specs(type)
    private val lo = DoubleArray(specs.size) { specs[it].lo }
    private val hi = DoubleArray(specs.size) { specs[it].hi }
    private val scale = DoubleArray(specs.size) { hi[it] - lo[it] }
    private val prior = DoubleArray(specs.size) { (hi[it] + lo[it]) / 2.0 }

    fun forwardAtNormalized(z: DoubleArray): DoubleArray {
        val p = SourceParams.fromArray(type, denormalize(z))
        return engine.predict(observations, p)
    }

    private val feasibility: (SourceParams) -> Boolean = when (type) {
        ModelType.DIKE -> { p -> (engine.model as DikeModel).isFeasible(p) }
        else -> { _ -> true }
    }

    fun objectiveNormalized(z: DoubleArray): Double {
        val p = SourceParams.fromArray(type, denormalize(z))
        if (!feasibility(p)) return Double.POSITIVE_INFINITY
        val pred = engine.predict(observations, p)
        val r = DoubleArray(observations.size) { dataVector[it] - pred[it] }
        val chi2 = covariance.quadratic(r)
        if (chi2.isNaN() || chi2.isInfinite()) return Double.POSITIVE_INFINITY
        return chi2 + regularization.penalty(z, lo, hi, priorCenterNormalized())
    }

    private fun priorCenterNormalized(): DoubleArray = DoubleArray(specs.size) { 0.5 }

    private fun normalize(a: DoubleArray) =
        DoubleArray(a.size) { (a[it] - lo[it]) / scale[it] }

    private fun denormalize(z: DoubleArray) =
        DoubleArray(z.size) { lo[it] + z[it] * scale[it] }

    fun run(seed: Long = 20260922L, starts: Int = 24, maxIter: Int = 400): List<StartResult> {
        val rng = Random(seed)
        val initial = buildStarts(starts, rng)
        return initial.mapIndexed { idx, z0 ->
            val nm = boundedNelderMead(z0, maxIter)
            StartResult(idx, denormalize(nm.z), nm.f, nm.iterations, nm.converged)
        }.sortedBy { it.objective }
    }

    private fun buildStarts(starts: Int, rng: Random): List<DoubleArray> {
        val d = specs.size
        // 1) 角点/中点的分层覆盖（按坐标轮转，避免指数爆炸）
        val base = mutableListOf(DoubleArray(d) { 0.5 })
        val cover = minOf(starts / 2, 12)
        for (k in 0 until cover) {
            val z = DoubleArray(d)
            for (i in 0 until d) {
                val v = ((k + 1) * 31 + i * 17) % 7
                z[i] = doubleArrayOf(0.15, 0.3, 0.5, 0.7, 0.85, 0.5, 0.5)[v]
            }
            base.add(z)
        }
        // 2) 确定性 LCG 微扰
        var state = 0x9E3779B97F4A7C15UL.toLong()
        fun lcg(): Double {
            state = (state * 6364136223846793005L + 1442695040888963407L)
            return ((state ushr 33).toDouble()) / (1L shl 31).toDouble()
        }
        while (base.size < starts) {
            val z = DoubleArray(d) { 0.1 + 0.8 * lcg() }
            base.add(z)
        }
        return base.take(starts)
    }

    private fun clamp(z: DoubleArray) =
        DoubleArray(z.size) { z[it].coerceIn(0.0, 1.0) }

    private fun boundedNelderMead(
        start: DoubleArray, maxIter: Int,
): NMResult {
        val d = start.size
        val simplex = Array(d + 1) { DoubleArray(d) }
        simplex[0] = clamp(start)
        for (i in 0 until d) {
            simplex[i + 1] = simplex[0].copyOf()
            simplex[i + 1][i] = (simplex[i + 1][i] + 0.12).coerceAtMost(0.98)
        }
        var f = DoubleArray(d + 1) { objectiveNormalized(simplex[it]) }
        val alpha = 1.0; val gamma = 2.0; val rho = 0.5; val sigma = 0.5
        var iter = 0
        var converged = false
        while (iter < maxIter) {
            iter++
            val order = (0..d).sortedBy { f[it] }.toIntArray()
            val s = Array(d + 1) { simplex[order[it]] }
            val fs = DoubleArray(d + 1) { f[order[it]] }
            val spread = fs.max() - fs.min()
            val zSpread = (0 until d).maxOf { j -> (0..d).maxOf { s[it][j] } - (0..d).minOf { s[it][j] } }
            if (spread < 1.0e-8 && zSpread < 1.0e-6) {
                converged = true
                return NMResult(s[0].copyOf(), fs[0], iter, true)
            }
            val centroid = DoubleArray(d)
            for (i in 0 until d) {
                var sum = 0.0
                for (k in 0 until d) sum += s[k][i]
                centroid[i] = sum / d
            }
            val worst = d
            // reflection
            val xr = DoubleArray(d) { centroid[it] + alpha * (centroid[it] - s[worst][it]) }
            val fr = objectiveNormalized(clamp(xr))
            if (fr < fs[d - 1] && fr >= fs[0]) {
                s[worst] = clamp(xr); fs[worst] = fr
            } else if (fr < fs[0]) {
                val xe = DoubleArray(d) { centroid[it] + gamma * (xr[it] - centroid[it]) }
                val fe = objectiveNormalized(clamp(xe))
                if (fe < fr) { s[worst] = clamp(xe); fs[worst] = fe }
                else { s[worst] = clamp(xr); fs[worst] = fr }
            } else {
                val xc = DoubleArray(d) { centroid[it] + rho * (s[worst][it] - centroid[it]) }
                val fc = objectiveNormalized(clamp(xc))
                if (fc < fs[worst]) {
                    s[worst] = clamp(xc); fs[worst] = fc
                } else {
                    for (k in 1..d) {
                        val shrunk = DoubleArray(d) { s[0][it] + sigma * (s[k][it] - s[0][it]) }
                        s[k] = clamp(shrunk); fs[k] = objectiveNormalized(s[k])
                    }
                }
            }
            for (k in 0..d) { simplex[k] = s[k]; f[k] = fs[k] }
        }
        val bestIdx = (0..d).minByOrNull { f[it] }!!
        return NMResult(simplex[bestIdx].copyOf(), f[bestIdx], iter, converged)
    }

    fun activeBounds(params: DoubleArray, tol: Double = 1.0e-3): ActiveBounds {
        val z = normalize(params)
        val details = mutableListOf<String>()
        var any = false
        var depthUp = false; var depthLo = false
        val sizeUp = mutableListOf<String>()
        specs.forEachIndexed { i, spec ->
            val nearLo = z[i] <= tol
            val nearHi = z[i] >= 1.0 - tol
            if (nearLo || nearHi) {
                any = true
                val side = if (nearHi) "上界" else "下界"
                details.add("参数 ${spec.name}（${spec.key}）=${"%.4f".format(params[i])} ${spec.unit} 触及${side} ${if (nearHi) spec.hi else spec.lo} ${spec.unit}")
                if (spec.key == "depth" && nearHi) depthUp = true
                if (spec.key == "depth" && nearLo) depthLo = true
                if (nearHi && spec.key in listOf("length", "width")) sizeUp.add(spec.key)
            }
        }
        return ActiveBounds(any, depthUp, depthLo, sizeUp, details)
    }
}

/**
 * Tikhonov 正则：在归一化参数空间中把解拉向先验中心。
 * penalty = lambda * sum_i w_i (z_i - zPrior_i)^2
 * lambda 为无量纲强度（用户滑杆），默认 0 表示纯加权最小二乘。
 */
class Regularization(
    val lambda: Double,
    val weights: DoubleArray? = null,
    val description: String = "Tikhonov 参数空间向心正则",
) {
    fun penalty(z: DoubleArray, lo: DoubleArray, hi: DoubleArray, zPrior: DoubleArray): Double {
        if (lambda == 0.0) return 0.0
        var s = 0.0
        for (i in z.indices) {
            val w = weights?.get(i) ?: 1.0
            s += w * (z[i] - zPrior[i]) * (z[i] - zPrior[i])
        }
        return lambda * s
    }
}
