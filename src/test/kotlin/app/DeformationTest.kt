package app

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeformationTest {
    private val dataset = Fixture.build().let { input ->
        val stations = input.stations.associate {
            it.id to Station(it.id, it.name, it.xKm, it.yKm, it.elevationKm, it.region,
                it.losEast?.let { east -> doubleArrayOf(east, it.losNorth!!, it.losUp!!) })
        }
        Dataset(
            1,
            input.code,
            input.name,
            input.crs,
            input.covarianceGroups.associate {
                it.code to CovarianceGroup(it.code, it.observationType, it.periodId, it.description, it.commonModeVariance, it.spatialCorrelationLengthKm)
            },
            stations,
            input.observations.map { Observation(it.stationId, it.observationType, it.component, it.periodId, it.displacementM, it.sigmaM, it.covarianceGroup) }
        )
    }

    @Test
    fun losVectorsAreUnitNormalizedAndConventionsAreFixed() {
        dataset.stations.values.filterNotNull().mapNotNull { it.los }.forEach { los ->
            assertEquals(1.0, losNorm(los), 1e-12)
        }
        assertEquals("up", dataset.crs.elevationPositive)
        assertTrue(dataset.crs.depthConvention.contains("positive downward"))
    }

    @Test
    fun dikeFitsFixtureAndPointReachesUpperDepthBoundary() {
        val dike = InversionEngine(dataset, Bounds(maxDepthKm = 8.0, maxSizeKm = 6.0))
            .solve(SourceType.DIKE, Bounds(maxDepthKm = 8.0, maxSizeKm = 6.0), 0.02, 12)
        assertTrue(dike.dataCost < 80.0, "dike should nearly recover synthetic field, was ${dike.dataCost}")
        assertTrue(abs(dike.parameters.depthKm - Fixture.truth.depthKm) < 0.6)
        assertTrue(abs((dike.parameters.lengthKm ?: 0.0) - 5.0) < 0.2)
        assertTrue(abs((dike.parameters.widthKm ?: 0.0) - 3.0) < 0.2)
        assertTrue(boundaryReports(dike.parameters, Bounds(maxDepthKm = 8.0, maxSizeKm = 6.0)).none { it.parameter == "depthKm" })

        val bounds = Bounds(maxDepthKm = 8.0, maxSizeKm = 6.0)
        val point = InversionEngine(dataset, bounds).solve(SourceType.POINT, bounds, 0.02, 16)
        val active = boundaryReports(point.parameters, bounds)
        assertEquals(8.0, point.parameters.depthKm, 1e-5)
        assertTrue(active.any { it.parameter == "depthKm" && it.bound == "upper" && it.active })
        assertTrue(point.dataCost < dike.dataCost * 1.25, "point and dike should be near-equivalent")
    }

    @Test
    fun commonModeErrorCreatesExplicitOffDiagonalCovariance() {
        val engine = InversionEngine(dataset)
        val matrix = engine.covarianceMatrix()
        val firstGnss = dataset.observations.indexOfFirst { it.covarianceGroup == "GNSS_E_2025A" }
        val secondGnss = dataset.observations.drop(firstGnss + 1).indexOfFirst { it.covarianceGroup == "GNSS_E_2025A" } + firstGnss + 1
        assertTrue(matrix[firstGnss, secondGnss] > 0.0)
        val differentGroup = dataset.observations.indexOfFirst { it.covarianceGroup == "INSAR_ASC_2025A" }
        assertEquals(0.0, matrix[firstGnss, differentGroup], 0.0)
        val summary = engine.covarianceSummary().first { it.group == "GNSS_E_2025A" }
        assertTrue(summary.effectiveIndependentSignals < summary.rawRows)
        assertTrue(summary.model.contains("common offset CME"))
    }

    @Test
    fun invalidLineOfSightIsRejected() {
        val bad = doubleArrayOf(0.5, 0.5, 0.5)
        val error = runCatching { requireUnitLos(bad) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("视线单位向量未归一化"))
    }
}
