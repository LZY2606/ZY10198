package app

import kotlinx.serialization.Serializable

@Serializable
enum class SourceType { POINT, DIKE }

@Serializable
data class CrsInfo(
    val name: String,
    val horizontalUnit: String,
    val verticalUnit: String,
    val elevationPositive: String,
    val depthConvention: String,
    val transformVersion: String,
    val transformFormula: String
)

@Serializable
data class CovarianceGroupInput(
    val code: String,
    val observationType: String,
    val periodId: String,
    val description: String,
    val commonModeVariance: Double,
    val spatialCorrelationLengthKm: Double = 0.0
)

@Serializable
data class StationInput(
    val id: String,
    val name: String,
    val xKm: Double,
    val yKm: Double,
    val elevationKm: Double,
    val region: String,
    val losEast: Double? = null,
    val losNorth: Double? = null,
    val losUp: Double? = null
)

@Serializable
data class ObservationInput(
    val stationId: String,
    val observationType: String,
    val component: String,
    val periodId: String,
    val displacementM: Double,
    val sigmaM: Double,
    val covarianceGroup: String
)

@Serializable
data class DatasetInput(
    val code: String,
    val name: String,
    val crs: CrsInfo,
    val covarianceGroups: List<CovarianceGroupInput>,
    val stations: List<StationInput>,
    val observations: List<ObservationInput>
)

data class Station(
    val id: String,
    val name: String,
    val xKm: Double,
    val yKm: Double,
    val elevationKm: Double,
    val region: String,
    val los: DoubleArray?
) {
    override fun equals(other: Any?): Boolean = other is Station && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

data class Observation(
    val stationId: String,
    val observationType: String,
    val component: String,
    val periodId: String,
    val valueM: Double,
    val sigmaM: Double,
    val covarianceGroup: String
)

data class CovarianceGroup(
    val code: String,
    val observationType: String,
    val periodId: String,
    val description: String,
    val commonModeVariance: Double,
    val spatialCorrelationLengthKm: Double
)

data class Dataset(
    val id: Long,
    val code: String,
    val name: String,
    val crs: CrsInfo,
    val groups: Map<String, CovarianceGroup>,
    val stations: Map<String, Station>,
    val observations: List<Observation>
)

data class SourceParameters(
    val type: SourceType,
    val xKm: Double,
    val yKm: Double,
    val depthKm: Double,
    val strength: Double,
    val lengthKm: Double? = null,
    val widthKm: Double? = null,
    val strikeDeg: Double? = null,
    val dipDeg: Double? = null
)

data class Bounds(
    val minXKm: Double = -9.0,
    val maxXKm: Double = 9.0,
    val minYKm: Double = -9.0,
    val maxYKm: Double = 9.0,
    val minDepthKm: Double = 0.2,
    val maxDepthKm: Double = 8.0,
    val minStrength: Double = -40.0,
    val maxStrength: Double = 40.0,
    val minSizeKm: Double = 0.15,
    val maxSizeKm: Double = 6.0,
    val minStrikeDeg: Double = 0.0,
    val maxStrikeDeg: Double = 180.0,
    val minDipDeg: Double = 0.0,
    val maxDipDeg: Double = 90.0
)

@Serializable
data class FitRequest(
    val datasetCode: String? = null,
    val modelType: SourceType,
    val regularization: Double = 0.02,
    val bounds: BoundsApi = BoundsApi(),
    val starts: Int = 12
)

@Serializable
data class BoundsApi(
    val minXKm: Double = -9.0,
    val maxXKm: Double = 9.0,
    val minYKm: Double = -9.0,
    val maxYKm: Double = 9.0,
    val minDepthKm: Double = 0.2,
    val maxDepthKm: Double = 8.0,
    val minStrength: Double = -40.0,
    val maxStrength: Double = 40.0,
    val minSizeKm: Double = 0.15,
    val maxSizeKm: Double = 6.0,
    val minStrikeDeg: Double = 0.0,
    val maxStrikeDeg: Double = 180.0,
    val minDipDeg: Double = 0.0,
    val maxDipDeg: Double = 90.0
)

fun BoundsApi.toDomain() = Bounds(
    minXKm, maxXKm, minYKm, maxYKm, minDepthKm, maxDepthKm,
    minStrength, maxStrength, minSizeKm, maxSizeKm,
    minStrikeDeg, maxStrikeDeg, minDipDeg, maxDipDeg
)

@Serializable
data class BoundaryReport(
    val parameter: String,
    val bound: String,
    val value: Double,
    val limit: Double,
    val active: Boolean
)

@Serializable
data class FitResponse(
    val runId: Long,
    val modelType: SourceType,
    val parameters: Map<String, Double>,
    val objective: Double,
    val dataWeightedRms: Double,
    val regularizationCost: Double,
    val effectiveObservations: Double,
    val rawObservations: Int,
    val boundaryActive: Boolean,
    val activeBoundaries: List<BoundaryReport>,
    val starts: List<Map<String, Double>>,
    val metricsByObservationType: Map<String, MetricSummary>,
    val metricsByRegion: Map<String, MetricSummary>,
    val covarianceSummary: List<GroupCovarianceSummary>,
    val crs: CrsInfo,
    val trace: PredictionTrace
)

@Serializable
data class MetricSummary(val count: Int, val weightedRms: Double, val meanResidualM: Double, val maxAbsResidualM: Double)

@Serializable
data class GroupCovarianceSummary(
    val group: String,
    val observationType: String,
    val periodId: String,
    val rawRows: Int,
    val effectiveIndependentSignals: Double,
    val commonModeSigmaM: Double,
    val model: String
)

@Serializable
data class PredictionTrace(
    val geometryVersion: String,
    val coordinateTransformVersion: String,
    val elevationConvention: String,
    val depthConvention: String,
    val sourceKernel: String,
    val generatedAtUtc: String
)

@Serializable
data class PredictionPoint(
    val stationId: String,
    val stationName: String,
    val observationType: String,
    val component: String,
    val periodId: String,
    val region: String,
    val observedM: Double,
    val predictedM: Double,
    val residualM: Double,
    val sigmaM: Double,
    val losUnitVector: List<Double>?,
    val losNorm: Double?,
    val geometryVersion: String,
    val coordinateTransformVersion: String
)

@Serializable
data class RunSummary(
    val id: Long,
    val datasetCode: String,
    val modelType: SourceType,
    val objective: Double,
    val dataWeightedRms: Double,
    val boundaryActive: Boolean,
    val createdAtUtc: String
)

@Serializable
data class CompareResponse(
    val runA: RunSummary,
    val runB: RunSummary,
    val differencesByObservationType: Map<String, ModelDifference>,
    val differencesByRegion: Map<String, ModelDifference>
)

@Serializable
data class ModelDifference(
    val count: Int,
    val meanPredictionDifferenceM: Double,
    val rmsPredictionDifferenceM: Double,
    val weightedRmsA: Double,
    val weightedRmsB: Double
)
