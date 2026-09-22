package app

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 协方差结构（显式共同模式误差）：
 *
 *   C = D + S S^T
 *
 * 其中 D = diag(white_i^2)，S 为 n x g 选择/载荷矩阵：
 *   S[i,k] = sigmaCme_k  （观测 i 属于组 k）
 * 即每条观测的 CME 载荷 = 其所在组的 sigmaCme（米）。
 *
 * 反演的加权目标函数  chi2 = r^T C^{-1} r；
 * 为避免显式构造巨大稠密相关矩阵，用 Woodbury 恒等式：
 *   C^{-1} = D^{-1} - D^{-1} S (I + S^T D^{-1} S)^{-1} S^T D^{-1}
 * 组矩阵仅 g x g（g = 协方差分组数量，通常为个位数/十几）。
 *
 * 注意：共同模式误差是“共享随机效应”，在协方差中显式表达；
 * 不允许把同一组观测复制多份、或按独立样本重复加权来伪造信息量。
 */
class CovarianceStructure(
    private val white: DoubleArray,
    private val groupIndex: IntArray,
    groupSigmas: DoubleArray,
) {
    val n: Int = white.size
    val groupCount: Int = groupSigmas.size

    private val invD: DoubleArray = DoubleArray(n) { 1.0 / (white[it] * white[it]) }
    // S 隐式存储：S[i, groupIndex[i]] = groupSigmas[groupIndex[i]]

    private val invSqrtD: DoubleArray = DoubleArray(n) { 1.0 / white[it] }
    private val storedGroupSigmas: DoubleArray = groupSigmas.copyOf()

    // B = I + S^T D^{-1} S（g x g），构造一次、分解一次
    private val b: Array<DoubleArray>
    private val bInv: Array<DoubleArray>

    init {
        val g = groupCount
        b = Array(g) { DoubleArray(g) }
        for (k in 0 until g) b[k][k] = 1.0
        for (i in 0 until n) {
            val gi = groupIndex[i]
            if (gi >= 0) b[gi][gi] += groupSigmas[gi] * groupSigmas[gi] * invD[i]
        }
        bInv = invertSymmetric(b)
    }

    /** 白化残差 z = D^{-1/2} r（诊断用）。 */
    fun whiten(r: DoubleArray): DoubleArray =
        DoubleArray(n) { r[it] * invSqrtD[it] }

    /** r^T C^{-1} r（加权平方残差）。 */
    fun quadratic(r: DoubleArray): Double {
        val u = DoubleArray(n) { r[it] * invD[it] }
        // S^T u（S 载荷按组聚合）
        val su = DoubleArray(groupCount)
        for (i in 0 until n) {
            val gi = groupIndex[i]
            if (gi >= 0) su[gi] += storedGroupSigmas[gi] * u[i]
        }
        var cross = 0.0
        for (a in 0 until groupCount) {
            var row = 0.0
            for (bb in 0 until groupCount) row += bInv[a][bb] * su[bb]
            cross += su[a] * row
        }
        var diag = 0.0
        for (i in 0 until n) diag += r[i] * u[i]
        return diag - cross
    }

    /** 0.5 * logdet C，用行列式引理：log|D| + log|I + S^T D^{-1} S|。 */
    fun halfLogDet(): Double {
        var logD = 0.0
        for (i in 0 until n) logD += ln(white[i] * white[i])
        val logB = logDeterminantSymmetric(b)
        return 0.5 * (logD + logB)
    }

    companion object {
        fun build(
            observations: List<Observation>,
            groups: List<CovarianceGroup>,
        ): CovarianceStructure {
            val gIndex = groups.withIndex().associate { it.value.id to it.index }
            val white = DoubleArray(observations.size)
            val gi = IntArray(observations.size)
            val sig = DoubleArray(groups.size) { groups[it].sigmaCme }
            observations.forEachIndexed { idx, o ->
                white[idx] = o.sigmaWhite
                gi[idx] = gIndex[o.groupId] ?: -1
            }
            return CovarianceStructure(white, gi, sig)
        }
    }
}

/** 稠密对称正定矩阵 Cholesky 求逆（仅用于 g x g 小组矩阵）。 */
fun invertSymmetric(a: Array<DoubleArray>): Array<DoubleArray> {
    val g = a.size
    val l = Array(g) { DoubleArray(g) }
    for (i in 0 until g) {
        for (j in 0..i) {
            var sum = a[i][j]
            for (k in 0 until j) sum -= l[i][k] * l[j][k]
            if (i == j) l[i][j] = sqrt(sum)
            else l[i][j] = sum / l[j][j]
        }
    }
    // 解 L L^T X = I
    val inv = Array(g) { DoubleArray(g) }
    for (col in 0 until g) {
        val y = DoubleArray(g)
        for (i in 0 until g) {
            var s = if (i == col) 1.0 else 0.0
            for (k in 0 until i) s -= l[i][k] * y[k]
            y[i] = s / l[i][i]
        }
        val x = DoubleArray(g)
        for (i in g - 1 downTo 0) {
            var s = y[i]
            for (k in i + 1 until g) s -= l[k][i] * x[k]
            x[i] = s / l[i][i]
        }
        for (i in 0 until g) inv[i][col] = x[i]
    }
    return inv
}

fun logDeterminantSymmetric(a: Array<DoubleArray>): Double {
    val g = a.size
    val l = Array(g) { DoubleArray(g) }
    var logDet = 0.0
    for (i in 0 until g) {
        for (j in 0..i) {
            var sum = a[i][j]
            for (k in 0 until j) sum -= l[i][k] * l[j][k]
            if (i == j) {
                l[i][j] = sqrt(sum)
                logDet += 2.0 * ln(l[i][j])
            } else l[i][j] = sum / l[j][j]
        }
    }
    return logDet
}
