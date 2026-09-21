package app

import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

class DeformationService(private val database: AppDatabase) {
    fun fit(request: FitRequest): Pair<FitResponse, List<PredictionPoint>> {
        val dataset = database.latestDataset() ?: error("数据集为空，请先导入固定 fixture")
        require(request.regularization >= 0.0) { "正则强度不能为负" }
        val bounds = request.bounds.toDomain()
        validateBounds(bounds)
        val engine = InversionEngine(dataset, bounds)
        val solved = engine.solve(request.modelType, bounds, request.regularization, request.starts.coerceIn(3, 40))
        val names = parameterNames(request.modelType)
        val values = parameterVector(solved.parameters)
        val parameterMap = names.mapIndexed { index, name -> name to values[index] }.toMap()
        val boundaries = boundaryReports(solved.parameters, bounds)
        val points = dataset.observations.mapIndexed { index, observation ->
            val station = dataset.stations.getValue(observation.stationId)
            PredictionPoint(
                stationId = station.id,
                stationName = station.name,
                observationType = observation.observationType,
                component = observation.component,
                periodId = observation.periodId,
                region = station.region,
                observedM = observation.valueM,
                predictedM = solved.predictions[index],
                residualM = observation.valueM - solved.predictions[index],
                sigmaM = observation.sigmaM,
                losUnitVector = station.los?.toList(),
                losNorm = station.los?.let(::losNorm),
                geometryVersion = GEOMETRY_VERSION,
                coordinateTransformVersion = dataset.crs.transformVersion
            )
        }
        val response = FitResponse(
            runId = 0,
            modelType = request.modelType,
            parameters = parameterMap,
            objective = solved.objective,
            dataWeightedRms = weightedRms(engine, solved.predictions),
            regularizationCost = solved.regularizationCost,
            effectiveObservations = effectiveObservationCount(engine),
            rawObservations = dataset.observations.size,
            boundaryActive = boundaries.isNotEmpty(),
            activeBoundaries = boundaries,
            starts = solved.starts.map { start -> names.mapIndexed { index, name -> name to start[index] }.toMap() },
            metricsByObservationType = metricsBy(points) { it.observationType },
            metricsByRegion = metricsBy(points) { it.region },
            covarianceSummary = solved.covarianceSummary,
            crs = dataset.crs,
            trace = PredictionTrace(
                geometryVersion = GEOMETRY_VERSION,
                coordinateTransformVersion = dataset.crs.transformVersion,
                elevationConvention = dataset.crs.elevationPositive,
                depthConvention = dataset.crs.depthConvention,
                sourceKernel = if (request.modelType == SourceType.POINT) "Mogi point pressure kernel" else "restricted rectangular opening: integrated Mogi Green functions",
                generatedAtUtc = Instant.now().toString()
            )
        )
        val runId = database.saveRun(dataset, request, response, points)
        return response.copy(runId = runId) to points
    }

    private fun weightedRms(engine: InversionEngine, predictions: DoubleArray): Double {
        val covariance = engine.covarianceMatrix()
        val n = engine.observations.size
        val residual = DoubleArray(n) { engine.observations[it].valueM - predictions[it] }
        val solvedResidual = covariance.solve(residual)
        val total = residual.indices.sumOf { residual[it] * solvedResidual[it] }
        return sqrt(total / n)
    }

    private fun effectiveObservationCount(engine: InversionEngine): Double {
        return engine.covarianceSummary().sumOf { it.effectiveIndependentSignals }
    }

    private fun metricsBy(points: List<PredictionPoint>, key: (PredictionPoint) -> String): Map<String, MetricSummary> =
        points.groupBy(key).toSortedMap().mapValues { (_, group) ->
            var square = 0.0
            group.forEach { square += (it.residualM / it.sigmaM) * (it.residualM / it.sigmaM) }
            MetricSummary(
                count = group.size,
                weightedRms = sqrt(square / group.size),
                meanResidualM = group.map { it.residualM }.average(),
                maxAbsResidualM = group.maxOf { abs(it.residualM) }
            )
        }

    private fun validateBounds(bounds: Bounds) {
        require(bounds.minDepthKm > 0.0 && bounds.maxDepthKm > bounds.minDepthKm) { "深度必须为正且上界大于下界" }
        require(bounds.maxSizeKm >= bounds.minSizeKm) { "尺寸上界必须不小于下界" }
        require(bounds.maxStrength > bounds.minStrength) { "强度范围无效" }
    }

    fun compare(firstId: Long, secondId: Long): CompareResponse {
        val firstResponse = database.runResponse(firstId) ?: error("运行 $firstId 不存在")
        val secondResponse = database.runResponse(secondId) ?: error("运行 $secondId 不存在")
        val firstPoints = database.predictions(firstId)
        val secondPoints = database.predictions(secondId)
        require(firstPoints.size == secondPoints.size) { "两个运行的观测场不一致，无法差分" }
        val summaries = database.runSummaries()
        val firstSummary = summaries.first { it.id == firstId }
        val secondSummary = summaries.first { it.id == secondId }
        return CompareResponse(
            firstSummary,
            secondSummary,
            differences(firstPoints, secondPoints) { it.observationType },
            differences(firstPoints, secondPoints) { it.region }
        )
    }

    private fun differences(
        first: List<PredictionPoint>,
        second: List<PredictionPoint>,
        key: (PredictionPoint) -> String
    ): Map<String, ModelDifference> = first.indices.groupBy { key(first[it]) }.toSortedMap().mapValues { (_, indices) ->
        val deltas = indices.map { first[it].predictedM - second[it].predictedM }
        val a = indices.map { first[it] }
        val b = indices.map { second[it] }
        ModelDifference(
            count = indices.size,
            meanPredictionDifferenceM = deltas.average(),
            rmsPredictionDifferenceM = sqrt(deltas.sumOf { it * it } / deltas.size),
            weightedRmsA = weightedRms(a),
            weightedRmsB = weightedRms(b)
        )
    }

    private fun weightedRms(points: List<PredictionPoint>): Double {
        val sum = points.sumOf { val r = it.residualM / it.sigmaM; r * r }
        return sqrt(sum / points.size)
    }
}
