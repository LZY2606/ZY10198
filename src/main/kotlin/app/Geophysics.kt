package app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val GEOMETRY_VERSION = "geometry-enu-1.0"
const val COORDINATE_TRANSFORM_VERSION = "identity-km-enu-1.0"

data class Vector3(val x: Double, val y: Double, val zUp: Double)

operator fun Vector3.times(value: Double) = Vector3(x * value, y * value, zUp * value)
operator fun Double.times(v: Vector3) = v * this

fun losNorm(los: DoubleArray): Double = sqrt(los[0] * los[0] + los[1] * los[1] + los[2] * los[2])

fun requireUnitLos(los: DoubleArray, tolerance: Double = 1e-6): DoubleArray {
    val norm = losNorm(los)
    require(abs(norm - 1.0) <= tolerance) {
        "视线单位向量未归一化: norm=$norm, tolerance=$tolerance"
    }
    return los
}

fun normalizedOrRejected(los: DoubleArray?, tolerance: Double = 1e-6): DoubleArray? {
    if (los == null) return null
    return requireUnitLos(los, tolerance)
}

private fun mogiDisplacement(receiver: Station, sourceX: Double, sourceY: Double, sourceDepth: Double): Vector3 {
    val dx = receiver.xKm - sourceX
    val dy = receiver.yKm - sourceY
    val dz = sourceDepth + receiver.elevationKm
    val radius = sqrt(dx * dx + dy * dy + dz * dz)
    val scale = 1000.0 * 0.75 / (4.0 * PI * radius * radius * radius)
    return Vector3(dx * scale, dy * scale, dz * scale)
}

private fun degToRad(value: Double): Double = value * PI / 180.0

fun planeFrame(strikeDeg: Double, dipDeg: Double): Triple<Vector3, Vector3, Vector3> {
    val strikeAngle = degToRad(strikeDeg)
    val dipAngle = degToRad(dipDeg)
    val strike = Vector3(sin(strikeAngle), cos(strikeAngle), 0.0)
    val downDip = Vector3(cos(strikeAngle) * cos(dipAngle), -sin(strikeAngle) * cos(dipAngle), -sin(dipAngle))
    val normal = Vector3(-cos(strikeAngle) * sin(dipAngle), sin(strikeAngle) * sin(dipAngle), cos(dipAngle))
    return Triple(strike, downDip, normal)
}

private fun addScaled(base: Vector3, direction: Vector3, amount: Double): Vector3 =
    Vector3(base.x + direction.x * amount, base.y + direction.y * amount, base.zUp + direction.zUp * amount)

fun predictVector(station: Station, parameters: SourceParameters, integrationGrid: Int = 5): Vector3 {
    if (parameters.type == SourceType.POINT) {
        return mogiDisplacement(station, parameters.xKm, parameters.yKm, parameters.depthKm) * parameters.strength
    }
    val length = parameters.lengthKm ?: 1.0
    val width = parameters.widthKm ?: 1.0
    val strikeDeg = parameters.strikeDeg ?: 0.0
    val dipDeg = parameters.dipDeg ?: 90.0
    val (strike, downDip, _) = planeFrame(strikeDeg, dipDeg)
    val center = Vector3(parameters.xKm, parameters.yKm, -parameters.depthKm)
    var sum = Vector3(0.0, 0.0, 0.0)
    val count = integrationGrid * integrationGrid
    for (ix in 0 until integrationGrid) {
        val along = ((ix + 0.5) / integrationGrid - 0.5) * length
        for (iz in 0 until integrationGrid) {
            val across = ((iz + 0.5) / integrationGrid - 0.5) * width
            var point = addScaled(center, strike, along)
            point = addScaled(point, downDip, across)
            val pointSource = mogiDisplacement(station, point.x, point.y, -point.zUp)
            sum = Vector3(sum.x + pointSource.x, sum.y + pointSource.y, sum.zUp + pointSource.zUp)
        }
    }
    val cellArea = length * width / count
    return sum * (parameters.strength * cellArea)
}

fun predictObservation(station: Station, observation: Observation, parameters: SourceParameters): Double {
    val predicted = predictVector(station, parameters)
    return when (observation.observationType) {
        "GNSS" -> when (observation.component) {
            "E" -> predicted.x
            "N" -> predicted.y
            "U" -> predicted.zUp
            else -> error("未知 GNSS 分量: ${observation.component}")
        }
        "INSAR" -> {
            val los = station.los ?: error("InSAR 站点缺少 LOS 单位向量")
            requireUnitLos(los)
            predicted.x * los[0] + predicted.y * los[1] + predicted.zUp * los[2]
        }
        else -> error("未知观测类型: ${observation.observationType}")
    }
}

fun clamp(value: Double, low: Double, high: Double): Double = min(high, max(low, value))

fun parameterVector(parameters: SourceParameters): DoubleArray = when (parameters.type) {
    SourceType.POINT -> doubleArrayOf(parameters.xKm, parameters.yKm, parameters.depthKm, parameters.strength)
    SourceType.DIKE -> doubleArrayOf(
        parameters.xKm,
        parameters.yKm,
        parameters.depthKm,
        parameters.strength,
        parameters.lengthKm ?: 1.0,
        parameters.widthKm ?: 1.0,
        parameters.strikeDeg ?: 0.0,
        parameters.dipDeg ?: 90.0
    )
}

fun fromParameterVector(type: SourceType, values: DoubleArray): SourceParameters = when (type) {
    SourceType.POINT -> SourceParameters(type, values[0], values[1], values[2], values[3])
    SourceType.DIKE -> SourceParameters(
        type,
        values[0],
        values[1],
        values[2],
        values[3],
        values[4],
        values[5],
        values[6],
        values[7]
    )
}

fun parameterNames(type: SourceType): List<String> = when (type) {
    SourceType.POINT -> listOf("xKm", "yKm", "depthKm", "strength")
    SourceType.DIKE -> listOf("xKm", "yKm", "depthKm", "strength", "lengthKm", "widthKm", "strikeDeg", "dipDeg")
}

fun lowerBounds(type: SourceType, bounds: Bounds): DoubleArray = when (type) {
    SourceType.POINT -> doubleArrayOf(bounds.minXKm, bounds.minYKm, bounds.minDepthKm, bounds.minStrength)
    SourceType.DIKE -> doubleArrayOf(
        bounds.minXKm, bounds.minYKm, bounds.minDepthKm, bounds.minStrength,
        bounds.minSizeKm, bounds.minSizeKm, bounds.minStrikeDeg, bounds.minDipDeg
    )
}

fun upperBounds(type: SourceType, bounds: Bounds): DoubleArray = when (type) {
    SourceType.POINT -> doubleArrayOf(bounds.maxXKm, bounds.maxYKm, bounds.maxDepthKm, bounds.maxStrength)
    SourceType.DIKE -> doubleArrayOf(
        bounds.maxXKm, bounds.maxYKm, bounds.maxDepthKm, bounds.maxStrength,
        bounds.maxSizeKm, bounds.maxSizeKm, bounds.maxStrikeDeg, bounds.maxDipDeg
    )
}

fun boundaryReports(parameters: SourceParameters, bounds: Bounds, tolerance: Double = 1e-5): List<BoundaryReport> {
    val lows = lowerBounds(parameters.type, bounds)
    val highs = upperBounds(parameters.type, bounds)
    val values = parameterVector(parameters)
    return parameterNames(parameters.type).mapIndexedNotNull { index, name ->
        val low = lows[index]
        val high = highs[index]
        val value = values[index]
        when {
            abs(value - low) <= tolerance * max(1.0, abs(low)) -> BoundaryReport(name, "lower", value, low, true)
            abs(high - value) <= tolerance * max(1.0, abs(high)) -> BoundaryReport(name, "upper", value, high, true)
            else -> null
        }
    }
}
