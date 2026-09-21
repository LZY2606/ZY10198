package app

import kotlin.math.sqrt

object Fixture {
    val crs = CrsInfo(
        name = "EPSG:32654 projected UTM northings/eastings, local ENU origin at volcano summit",
        horizontalUnit = "kilometre relative to fixed summit origin",
        verticalUnit = "kilometre orthometric elevation",
        elevationPositive = "up",
        depthConvention = "positive downward below local topographic reference; no sign mixing with elevation",
        transformVersion = COORDINATE_TRANSFORM_VERSION,
        transformFormula = "east=x, north=y, up=elevation; stored positions are already local ENU kilometres"
    )

    private val groupsInput = listOf(
        CovarianceGroupInput("GNSS_E_2025A", "GNSS", "2025A", "GNSS east common mode for campaign 2025A", 0.00000225),
        CovarianceGroupInput("GNSS_N_2025A", "GNSS", "2025A", "GNSS north common mode for campaign 2025A", 0.00000225),
        CovarianceGroupInput("GNSS_U_2025A", "GNSS", "2025A", "GNSS vertical common mode for campaign 2025A", 0.00000900),
        CovarianceGroupInput("INSAR_ASC_2025A", "INSAR", "2025A", "ascending InSAR atmospheric/common scene error", 0.00000144, 2.4),
        CovarianceGroupInput("INSAR_DSC_2025B", "INSAR", "2025B", "descending InSAR atmospheric/common scene error", 0.00000169, 2.0)
    )

    private val stationCoordinates = listOf(
        Quad("G01", "北麓GNSS", 0.0, 5.5, 0.10, "north"),
        Quad("G02", "南坡GNSS", 0.0, -5.2, 0.35, "south"),
        Quad("G03", "东台GNSS", 5.0, 1.1, 0.22, "east"),
        Quad("G04", "西脊GNSS", -5.4, -0.8, 0.45, "west"),
        Quad("G05", "东北锥GNSS", 3.9, 3.8, 0.18, "east"),
        Quad("G06", "西南谷GNSS", -3.7, -4.0, 0.30, "south"),
        Quad("G07", "西北台GNSS", -4.2, 3.3, 0.26, "north"),
        Quad("G08", "东南岸GNSS", 3.4, -4.4, 0.12, "south"),
        Quad("G09", "山顶北GNSS", 0.0, 1.8, 1.10, "summit"),
        Quad("G10", "山顶西GNSS", -1.7, 0.0, 1.25, "summit"),
        Quad("G11", "山顶东GNSS", 1.8, 0.0, 1.18, "summit"),
        Quad("G12", "山顶南GNSS", 0.0, -1.9, 1.05, "summit")
    )

    private data class Quad(val id: String, val name: String, val x: Double, val y: Double, val z: Double, val region: String)

    private val gnssStations = stationCoordinates.map {
        StationInput(it.id, it.name, it.x, it.y, it.z, it.region)
    }

    private val insarCoordinates = listOf(
        Quad("I01", "升轨北", -2.0, 6.0, 0.2, "north"),
        Quad("I02", "升轨东", 6.1, 1.5, 0.1, "east"),
        Quad("I03", "升轨南", 1.0, -6.2, 0.2, "south"),
        Quad("I04", "升轨西", -6.0, -1.7, 0.3, "west"),
        Quad("I05", "升轨山顶", 0.0, 0.0, 1.2, "summit"),
        Quad("I06", "降轨东北", 4.5, 4.0, 0.2, "east"),
        Quad("I07", "降轨西北", -4.8, 3.8, 0.3, "north"),
        Quad("I08", "降轨东南", 4.8, -3.5, 0.1, "south"),
        Quad("I09", "降轨西南", -2.5, -5.8, 0.2, "south"),
        Quad("I10", "降轨山顶", 0.8, -0.7, 1.1, "summit")
    )

    private val ascendingLos = doubleArrayOf(-0.450, -0.120, sqrt(1.0 - 0.450 * 0.450 - 0.120 * 0.120))
    private val descendingLos = doubleArrayOf(0.420, 0.180, sqrt(1.0 - 0.420 * 0.420 - 0.180 * 0.180))

    private val insarStations = insarCoordinates.mapIndexed { index, q ->
        val los = if (index < 5) ascendingLos else descendingLos
        StationInput(q.id, q.name, q.x, q.y, q.z, q.region, los[0], los[1], los[2])
    }

    val truth = SourceParameters(
        type = SourceType.DIKE,
        xKm = 0.0,
        yKm = 0.0,
        depthKm = 7.8,
        strength = 0.004,
        lengthKm = 5.0,
        widthKm = 3.0,
        strikeDeg = 90.0,
        dipDeg = 90.0
    )

    fun build(): DatasetInput {
        val stations = gnssStations + insarStations
        val stationMap = stations.associate { input ->
            input.id to Station(
                input.id,
                input.name,
                input.xKm,
                input.yKm,
                input.elevationKm,
                input.region,
                input.losEast?.let { doubleArrayOf(it, input.losNorth!!, input.losUp!!) }?.also { los -> requireUnitLos(los) }
            )
        }
        val observations = mutableListOf<ObservationInput>()
        var noiseIndex = 0
        gnssStations.forEach { input ->
            val station = stationMap.getValue(input.id)
            listOf("E" to "GNSS_E_2025A", "N" to "GNSS_N_2025A", "U" to "GNSS_U_2025A").forEach { (component, group) ->
                val syntheticObservation = Observation(station.id, "GNSS", component, "2025A", 0.0, 0.004, group)
                val deterministicNoise = deterministicNoise(noiseIndex++)
                val sigma = if (component == "U") 0.008 else 0.004
                observations += ObservationInput(
                    station.id,
                    "GNSS",
                    component,
                    "2025A",
                    predictObservation(station, syntheticObservation, truth) + deterministicNoise,
                    sigma,
                    group
                )
            }
        }
        insarStations.forEachIndexed { index, input ->
            val station = stationMap.getValue(input.id)
            val period = if (index < 5) "2025A" else "2025B"
            val group = if (index < 5) "INSAR_ASC_2025A" else "INSAR_DSC_2025B"
            val syntheticObservation = Observation(station.id, "INSAR", "LOS", period, 0.0, 0.005, group)
            observations += ObservationInput(
                station.id,
                "INSAR",
                "LOS",
                period,
                predictObservation(station, syntheticObservation, truth) + deterministicNoise(noiseIndex++),
                0.005,
                group
            )
        }
        return DatasetInput("fixture-2025", "2025 复式火山近等价点源/岩墙观测场", crs, groupsInput, stations, observations)
    }

    private fun deterministicNoise(index: Int): Double {
        val sequence = doubleArrayOf(0.0007, -0.0005, 0.0009, -0.0008, 0.0003, -0.0006, 0.0005, -0.0002)
        return sequence[index % sequence.size]
    }
}
