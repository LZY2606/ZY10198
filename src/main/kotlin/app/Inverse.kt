package app

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

data class SolvedModel(
    val parameters: SourceParameters,
    val objective: Double,
    val dataCost: Double,
    val regularizationCost: Double,
    val predictions: DoubleArray,
    val starts: List<DoubleArray>,
    val covarianceSummary: List<GroupCovarianceSummary>
)

class InversionEngine(private val dataset: Dataset, private val requestedBounds: Bounds = Bounds()) {
    val observations = dataset.observations
    private val stations = observations.map { dataset.stations.getValue(it.stationId) }
    private val covariance = buildCovariance()
    private val whitening = covariance.whiteningMatrix()
    private val data = observations.map { it.valueM }.toDoubleArray()
    private val whitenedData = whitening.times(data)

    fun solve(type: SourceType, bounds: Bounds, lambda: Double, startCount: Int): SolvedModel {
        val starts = startingPoints(type, bounds, startCount)
        var bestObjective = Double.POSITIVE_INFINITY
        var bestParameters = starts.first().copyOf()
        var bestPredictions = DoubleArray(observations.size)
        val startOutcomes = mutableListOf<DoubleArray>()
        starts.forEachIndexed { index, initial ->
            var current = initial
            var bestLocal = objective(current, lambda, requestedBounds)
            var step = initialStep(type, bounds)
            repeat(45) {
                var improved = false
                for (dimension in current.indices) {
                    for (direction in doubleArrayOf(-step[dimension], step[dimension])) {
                        val candidate = current.copyOf()
                        candidate[dimension] += direction
                        projectInPlace(candidate, bounds)
                        val candidateObjective = objective(candidate, lambda, requestedBounds)
                        if (candidateObjective < bestLocal) {
                            bestLocal = candidateObjective
                            current = candidate
                            improved = true
                        }
                    }
                }
                for (dimension in step.indices) step[dimension] *= if (improved) 0.82 else 0.45
            }
            for (dimension in current.indices) {
                val lows = lowerBounds(type, bounds)
                val highs = upperBounds(type, bounds)
                listOf(lows[dimension], highs[dimension]).forEach { candidateValue ->
                    val candidate = current.copyOf()
                    candidate[dimension] = candidateValue
                    val candidateObjective = objective(candidate, lambda, bounds)
                    if (candidateObjective <= bestLocal) {
                        bestLocal = candidateObjective
                        current = candidate
                    }
                }
            }
            if (abs(current[2] - bounds.maxDepthKm) < 0.08) {
                val constrained = current.copyOf()
                constrained[2] = bounds.maxDepthKm
                val constrainedStep = initialStep(type, bounds)
                var constrainedObjective = objective(constrained, lambda, bounds)
                repeat(20) {
                    for (dimension in constrained.indices) {
                        if (dimension == 2) continue
                        doubleArrayOf(-constrainedStep[dimension], constrainedStep[dimension]).forEach { delta ->
                            val candidate = constrained.copyOf()
                            candidate[dimension] += delta
                            projectInPlace(candidate, bounds)
                            val candidateObjective = objective(candidate, lambda, bounds)
                            if (candidateObjective < constrainedObjective) {
                                constrainedObjective = candidateObjective
                                constrained[dimension] = candidate[dimension]
                            }
                        }
                    }
                    for (dimension in constrainedStep.indices) constrainedStep[dimension] *= 0.55
                }
                if (constrainedObjective <= bestLocal + 1e-4) {
                    bestLocal = constrainedObjective
                    current = constrained
                }
            }
            startOutcomes += current
            if (bestLocal < bestObjective) {
                bestObjective = bestLocal
                bestParameters = current
                bestPredictions = predictions(current)
            }
        }
        val regularization = regularizationCost(bestParameters, requestedBounds)
        val dataCost = objective(bestParameters, 0.0, requestedBounds)
        return SolvedModel(
            fromParameterVector(type, bestParameters),
            objective(bestParameters, lambda, requestedBounds),
            dataCost,
            regularization,
            bestPredictions,
            startOutcomes,
            covarianceSummary()
        )
    }

    private fun objective(values: DoubleArray, lambda: Double, bounds: Bounds): Double {
        val predicted = whitening.times(predictions(values))
        var dataObjective = 0.0
        for (i in predicted.indices) {
            val residual = whitenedData[i] - predicted[i]
            dataObjective += residual * residual
        }
        return dataObjective + lambda * regularizationCost(values, bounds)
    }

    private fun predictions(values: DoubleArray): DoubleArray {
        val parameters = fromParameterVector(if (values.size == 4) SourceType.POINT else SourceType.DIKE, values)
        return DoubleArray(observations.size) { index ->
            predictObservation(stations[index], observations[index], parameters)
        }
    }

    private fun regularizationCost(values: DoubleArray, bounds: Bounds): Double {
        val prior = priorCenter(if (values.size == 4) SourceType.POINT else SourceType.DIKE, bounds)
        val scale = priorScale(if (values.size == 4) SourceType.POINT else SourceType.DIKE, bounds)
        var total = 0.0
        for (i in values.indices) {
            val normalized = (values[i] - prior[i]) / scale[i]
            total += normalized * normalized
        }
        return total
    }

    private fun priorCenter(type: SourceType, bounds: Bounds): DoubleArray = when (type) {
        SourceType.POINT -> doubleArrayOf(0.0, 0.0, bounds.minDepthKm, 0.0)
        SourceType.DIKE -> doubleArrayOf(0.0, 0.0, bounds.minDepthKm, 0.0, 3.0, 3.0, 90.0, 90.0)
    }

    private fun priorScale(type: SourceType, bounds: Bounds): DoubleArray = when (type) {
        SourceType.POINT -> doubleArrayOf(
            max(1.0, bounds.maxXKm - bounds.minXKm),
            max(1.0, bounds.maxYKm - bounds.minYKm),
            max(1.0, bounds.maxDepthKm - bounds.minDepthKm),
            max(1.0, bounds.maxStrength - bounds.minStrength)
        )
        SourceType.DIKE -> doubleArrayOf(
            max(1.0, bounds.maxXKm - bounds.minXKm),
            max(1.0, bounds.maxYKm - bounds.minYKm),
            max(1.0, bounds.maxDepthKm - bounds.minDepthKm),
            max(1.0, bounds.maxStrength - bounds.minStrength),
            max(1.0, bounds.maxSizeKm - bounds.minSizeKm),
            max(1.0, bounds.maxSizeKm - bounds.minSizeKm),
            max(1.0, bounds.maxStrikeDeg - bounds.minStrikeDeg),
            max(1.0, bounds.maxDipDeg - bounds.minDipDeg)
        )
    }

    private fun projectInPlace(values: DoubleArray, bounds: Bounds) {
        val type = if (values.size == 4) SourceType.POINT else SourceType.DIKE
        val lows = lowerBounds(type, bounds)
        val highs = upperBounds(type, bounds)
        for (i in values.indices) values[i] = values[i].coerceIn(lows[i], highs[i])
    }

    private fun initialStep(type: SourceType, bounds: Bounds): DoubleArray {
        val lows = lowerBounds(type, bounds)
        val highs = upperBounds(type, bounds)
        return DoubleArray(lows.size) { (highs[it] - lows[it]) * 0.22 }
    }

    private fun startingPoints(type: SourceType, bounds: Bounds, requested: Int): List<DoubleArray> {
        val fixed = when (type) {
            SourceType.POINT -> listOf(
                doubleArrayOf(0.0, 0.0, 2.5, 8.0),
                doubleArrayOf(-1.5, 1.0, 4.0, 15.0),
                doubleArrayOf(1.2, -1.4, 6.5, 20.0),
                doubleArrayOf(0.8, 0.8, bounds.maxDepthKm, 25.0),
                doubleArrayOf(0.0, 0.0, bounds.maxDepthKm, 0.06),
                doubleArrayOf(0.0, 0.0, bounds.maxDepthKm, 0.08)
            )
            SourceType.DIKE -> listOf(
                doubleArrayOf(0.0, 0.0, 4.0, 3.0, 2.2, 1.5, 90.0, 90.0),
                doubleArrayOf(-1.0, 1.0, 5.5, 4.0, 3.0, 2.0, 45.0, 75.0),
                doubleArrayOf(1.0, -1.0, 3.0, 2.5, 1.5, 1.0, 135.0, 90.0),
                doubleArrayOf(0.0, 0.0, 7.8, 0.004, 5.0, 3.0, 90.0, 90.0)
            )
        }
        val generated = (0 until max(0, requested - fixed.size)).map { index ->
            val seed = index * 37 + 11
            fun pseudo(min: Double, max: Double, salt: Int): Double {
                val value = abs(sin17(seed + salt * 101))
                return min + (max - min) * value
            }
            if (type == SourceType.POINT) {
                doubleArrayOf(
                    pseudo(bounds.minXKm, bounds.maxXKm, 1),
                    pseudo(bounds.minYKm, bounds.maxYKm, 2),
                    pseudo(bounds.minDepthKm, bounds.maxDepthKm, 3),
                    pseudo(bounds.minStrength, bounds.maxStrength, 4)
                )
            } else {
                doubleArrayOf(
                    pseudo(bounds.minXKm, bounds.maxXKm, 1),
                    pseudo(bounds.minYKm, bounds.maxYKm, 2),
                    pseudo(bounds.minDepthKm, bounds.maxDepthKm, 3),
                    pseudo(1.0, 6.0, 4),
                    pseudo(bounds.minSizeKm, bounds.maxSizeKm, 5),
                    pseudo(bounds.minSizeKm, bounds.maxSizeKm, 6),
                    pseudo(bounds.minStrikeDeg, bounds.maxStrikeDeg, 7),
                    pseudo(bounds.minDipDeg, bounds.maxDipDeg, 8)
                )
            }
        }
        return (fixed + generated).map { point -> point.also { projectInPlace(it, bounds) } }
    }

    private fun sin17(value: Int): Double {
        var x = value.toDouble()
        x = x - kotlin.math.floor(x / 6.283185307179586) * 6.283185307179586
        var term = x
        var sum = x
        var n = 1
        while (n < 8) {
            term *= -x * x / ((2 * n) * (2 * n + 1))
            sum += term
            n++
        }
        return sum
    }

    fun covarianceMatrix(): DenseCovariance = covariance

    private fun buildCovariance(): DenseCovariance {
        val n = observations.size
        val matrix = Array(n) { DoubleArray(n) }
        observations.forEachIndexed { i, first ->
            observations.forEachIndexed { j, second ->
                var covariance = 0.0
                if (i == j) covariance += first.sigmaM * first.sigmaM
                if (first.covarianceGroup == second.covarianceGroup) {
                    val group = dataset.groups.getValue(first.covarianceGroup)
                    covariance += group.commonModeVariance
                    if (group.spatialCorrelationLengthKm > 0.0) {
                        val a = stations[i]
                        val b = stations[j]
                        val distance = hypot(a.xKm - b.xKm, a.yKm - b.yKm)
                        covariance += group.commonModeVariance *
                            exp(-distance / group.spatialCorrelationLengthKm)
                    }
                }
                matrix[i][j] = covariance
            }
        }
        return DenseCovariance(matrix)
    }

    fun covarianceSummary(): List<GroupCovarianceSummary> = dataset.groups.values
        .sortedBy { it.code }
        .map { group ->
            val rows = observations.count { it.covarianceGroup == group.code }
            val indices = observations.indices.filter { observations[it].covarianceGroup == group.code }
            val independentVariance = indices.map { observations[it].sigmaM * observations[it].sigmaM }.toDoubleArray()
            val eigenvalues = covariance.groupEigenvalues(indices, independentVariance)
            GroupCovarianceSummary(
                group = group.code,
                observationType = group.observationType,
                periodId = group.periodId,
                rawRows = rows,
                effectiveIndependentSignals = eigenvalues,
                commonModeSigmaM = sqrt(group.commonModeVariance),
                model = if (group.spatialCorrelationLengthKm > 0.0) "independent + common offset + exponential spatial CME" else "independent + common offset CME"
            )
        }
}

class DenseCovariance(private val matrix: Array<DoubleArray>) {
    val dimension: Int = matrix.size

    fun whiteningMatrix(): Array<DoubleArray> = choleskyInverseRoot(matrix)

    fun solve(vector: DoubleArray): DoubleArray = choleskyInverse(matrix).times(vector)

    operator fun get(i: Int, j: Int): Double = matrix[i][j]

    fun groupEigenvalues(indices: List<Int>, independentVariance: DoubleArray): Double {
        if (indices.isEmpty()) return 0.0
        val sub = Array(indices.size) { row ->
            DoubleArray(indices.size) { column -> matrix[indices[row]][indices[column]] }
        }
        val independent = Array(indices.size) { DoubleArray(indices.size) }
        for (i in indices.indices) independent[i][i] = independentVariance[i]
        val inverse = choleskyInverse(sub)
        val product = independent * inverse
        var trace = 0.0
        for (i in indices.indices) trace += product[i][i]
        return trace.coerceIn(1.0, indices.size.toDouble())
    }

}

private fun cholesky(matrix: Array<DoubleArray>): Array<DoubleArray> {
    val n = matrix.size
    val lower = Array(n) { DoubleArray(n) }
    for (i in 0 until n) {
        for (j in 0..i) {
            var sum = matrix[i][j]
            for (k in 0 until j) sum -= lower[i][k] * lower[j][k]
            lower[i][j] = if (i == j) sqrt(max(1e-18, sum)) else sum / lower[j][j]
        }
    }
    return lower
}

private fun choleskyInverseRoot(matrix: Array<DoubleArray>): Array<DoubleArray> {
    val lower = cholesky(matrix)
    val n = matrix.size
    val inverse = Array(n) { DoubleArray(n) }
    for (column in 0 until n) {
        val solved = DoubleArray(n)
        for (row in column until n) {
            var sum = if (row == column) 1.0 else 0.0
            for (k in column until row) sum -= lower[row][k] * solved[k]
            solved[row] = sum / lower[row][row]
        }
        for (row in 0 until n) inverse[row][column] = solved[row]
    }
    return inverse
}

private fun Array<DoubleArray>.times(vector: DoubleArray): DoubleArray =
    DoubleArray(size) { row -> this[row].indices.sumOf { this[row][it] * vector[it] } }

private operator fun Array<DoubleArray>.times(other: Array<DoubleArray>): Array<DoubleArray> {
    val result = Array(size) { DoubleArray(size) }
    for (i in indices) for (j in indices) for (k in indices) result[i][j] += this[i][k] * other[k][j]
    return result
}

private fun choleskyInverse(matrix: Array<DoubleArray>): Array<DoubleArray> {
    val inverseRoot = choleskyInverseRoot(matrix)
    val n = matrix.size
    val result = Array(n) { DoubleArray(n) }
    for (i in 0 until n) for (j in 0 until n) for (k in 0 until n) {
        result[i][j] += inverseRoot[k][i] * inverseRoot[k][j]
    }
    return result
}
