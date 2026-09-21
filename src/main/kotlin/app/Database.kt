package app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.time.Instant

class AppDatabase(private val path: String) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun connect(): Connection {
        if (path != ":memory:") {
            Files.createDirectories(Path.of(path).toAbsolutePath().parent)
        }
        return DriverManager.getConnection("jdbc:sqlite:$path")
    }

    fun migrate() = connect().use { connection ->
        connection.createStatement().executeUpdate(
            """
            create table if not exists datasets (
              id integer primary key,
              code text unique not null,
              name text not null,
              payload text not null,
              imported_at_utc text not null
            )
            """.trimIndent()
        )
        connection.createStatement().executeUpdate(
            """
            create table if not exists runs (
              id integer primary key,
              dataset_id integer not null references datasets(id),
              model_type text not null,
              request_json text not null,
              response_json text not null,
              parameters_json text not null,
              objective real not null,
              data_weighted_rms real not null,
              boundary_active integer not null,
              created_at_utc text not null
            )
            """.trimIndent()
        )
        connection.createStatement().executeUpdate(
            """
            create table if not exists predictions (
              id integer primary key,
              run_id integer not null references runs(id),
              station_id text not null,
              observation_type text not null,
              component text not null,
              period_id text not null,
              region text not null,
              observed_m real not null,
              predicted_m real not null,
              residual_m real not null,
              sigma_m real not null,
              los_json text,
              geometry_version text not null,
              coordinate_transform_version text not null
            )
            """.trimIndent()
        )
    }

    fun seedIfEmpty() {
        connect().use { connection ->
            val count = connection.prepareStatement("select count(*) from datasets").executeQuery().use { it.getInt(1) }
            if (count == 0) importDataset(connection, Fixture.build())
        }
    }

    fun replaceFixture(): Long = connect().use { connection ->
        connection.autoCommit = false
        try {
            connection.createStatement().executeUpdate("delete from predictions")
            connection.createStatement().executeUpdate("delete from runs")
            connection.createStatement().executeUpdate("delete from datasets")
            val id = importDataset(connection, Fixture.build())
            connection.commit()
            id
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun importDataset(connection: Connection, input: DatasetInput): Long {
        validateDataset(input)
        val statement = connection.prepareStatement(
            "insert into datasets(code, name, payload, imported_at_utc) values (?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS
        )
        statement.setString(1, input.code)
        statement.setString(2, input.name)
        statement.setString(3, json.encodeToString(input))
        statement.setString(4, Instant.now().toString())
        statement.executeUpdate()
        return statement.generatedKeys.use { if (it.next()) it.getLong(1) else error("dataset id missing") }
    }

    fun latestPayload(): String? = connect().use { connection ->
        val rs = connection.prepareStatement("select payload from datasets order by id desc limit 1").executeQuery()
        if (rs.next()) rs.getString(1) else null
    }

    fun latestDataset(): Dataset? = connect().use { connection ->
        val rs = connection.prepareStatement(
            "select id, code, name, payload from datasets order by id desc limit 1"
        ).executeQuery()
        if (!rs.next()) null else decodeDataset(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))
    }

    private fun decodeDataset(id: Long, code: String, name: String, payload: String): Dataset {
        val input = json.decodeFromString<DatasetInput>(payload)
        validateDataset(input)
        val groups = input.covarianceGroups.associate {
            it.code to CovarianceGroup(it.code, it.observationType, it.periodId, it.description, it.commonModeVariance, it.spatialCorrelationLengthKm)
        }
        val stations = input.stations.associate {
            it.id to Station(
                it.id,
                it.name,
                it.xKm,
                it.yKm,
                it.elevationKm,
                it.region,
                it.losEast?.let { east -> doubleArrayOf(east, it.losNorth!!, it.losUp!!) }
            )
        }
        val observations = input.observations.map {
            Observation(it.stationId, it.observationType, it.component, it.periodId, it.displacementM, it.sigmaM, it.covarianceGroup)
        }
        return Dataset(id, code, name, input.crs, groups, stations, observations)
    }

    fun saveRun(dataset: Dataset, request: FitRequest, response: FitResponse, points: List<PredictionPoint>): Long = connect().use { connection ->
        connection.autoCommit = false
        try {
            val runStatement = connection.prepareStatement(
                """
                insert into runs(dataset_id, model_type, request_json, response_json, parameters_json,
                  objective, data_weighted_rms, boundary_active, created_at_utc)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            )
            runStatement.setLong(1, dataset.id)
            runStatement.setString(2, request.modelType.name)
            runStatement.setString(3, json.encodeToString(request))
            runStatement.setString(4, json.encodeToString(response))
            runStatement.setString(5, json.encodeToString(response.parameters))
            runStatement.setDouble(6, response.objective)
            runStatement.setDouble(7, response.dataWeightedRms)
            runStatement.setInt(8, if (response.boundaryActive) 1 else 0)
            runStatement.setString(9, response.trace.generatedAtUtc)
            runStatement.executeUpdate()
            val runId = runStatement.generatedKeys.use { if (it.next()) it.getLong(1) else error("run id missing") }
            val predictionStatement = connection.prepareStatement(
                """
                insert into predictions(run_id, station_id, observation_type, component, period_id, region,
                  observed_m, predicted_m, residual_m, sigma_m, los_json, geometry_version, coordinate_transform_version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            )
            points.forEach { point ->
                predictionStatement.setLong(1, runId)
                predictionStatement.setString(2, point.stationId)
                predictionStatement.setString(3, point.observationType)
                predictionStatement.setString(4, point.component)
                predictionStatement.setString(5, point.periodId)
                predictionStatement.setString(6, point.region)
                predictionStatement.setDouble(7, point.observedM)
                predictionStatement.setDouble(8, point.predictedM)
                predictionStatement.setDouble(9, point.residualM)
                predictionStatement.setDouble(10, point.sigmaM)
                predictionStatement.setString(11, point.losUnitVector?.let(json::encodeToString))
                predictionStatement.setString(12, point.geometryVersion)
                predictionStatement.setString(13, point.coordinateTransformVersion)
                predictionStatement.addBatch()
            }
            predictionStatement.executeBatch()
            connection.commit()
            runId
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    fun runSummaries(): List<RunSummary> = connect().use { connection ->
        connection.createStatement().executeQuery(
            """
            select r.id, d.code, r.model_type, r.objective, r.data_weighted_rms, r.boundary_active, r.created_at_utc
            from runs r join datasets d on d.id = r.dataset_id order by r.id
            """.trimIndent()
        ).readRows {
            RunSummary(
                it.getLong(1),
                it.getString(2),
                SourceType.valueOf(it.getString(3)),
                it.getDouble(4),
                it.getDouble(5),
                it.getInt(6) == 1,
                it.getString(7)
            )
        }
    }

    fun runResponse(runId: Long): FitResponse? = connect().use { connection ->
        val rs = connection.prepareStatement("select response_json from runs where id = ?").apply { setLong(1, runId) }.executeQuery()
        if (rs.next()) json.decodeFromString<FitResponse>(rs.getString(1)) else null
    }

    fun predictions(runId: Long): List<PredictionPoint> = connect().use { connection ->
        val rs = connection.prepareStatement("select * from predictions where run_id = ? order by id").apply { setLong(1, runId) }.executeQuery()
        rs.readRows {
            PredictionPoint(
                it.getString("station_id"),
                "",
                it.getString("observation_type"),
                it.getString("component"),
                it.getString("period_id"),
                it.getString("region"),
                it.getDouble("observed_m"),
                it.getDouble("predicted_m"),
                it.getDouble("residual_m"),
                it.getDouble("sigma_m"),
                it.getString("los_json")?.let(json::decodeFromString),
                null,
                it.getString("geometry_version"),
                it.getString("coordinate_transform_version")
            )
        }
    }

    fun exportRun(runId: Long): String? = connect().use { connection ->
        val rs = connection.prepareStatement(
            """
            select json_object('run', json(r.response_json), 'dataset', json(d.payload),
              'predictions', (select json_group_array(json_object(
                 'stationId', station_id, 'observationType', observation_type, 'component', component,
                 'periodId', period_id, 'region', region, 'observedM', observed_m, 'predictedM', predicted_m,
                 'residualM', residual_m, 'sigmaM', sigma_m, 'geometryVersion', geometry_version,
                 'coordinateTransformVersion', coordinate_transform_version))
              from predictions where run_id = r.id))
            from runs r join datasets d on d.id = r.dataset_id where r.id = ?
            """.trimIndent()
        ).apply { setLong(1, runId) }.executeQuery()
        if (rs.next()) rs.getString(1) else null
    }
}

private fun <T> java.sql.ResultSet.readRows(mapper: (java.sql.ResultSet) -> T): List<T> {
    val result = mutableListOf<T>()
    while (next()) result += mapper(this)
    return result
}

fun validateDataset(input: DatasetInput) {
    require(input.elevationPositiveConvention()) { "高程必须声明为 up 正方向" }
    input.stations.forEach { station ->
        if (station.losEast != null || station.losNorth != null || station.losUp != null) {
            val los = doubleArrayOf(
                station.losEast ?: error("${station.id} LOS east missing"),
                station.losNorth ?: error("${station.id} LOS north missing"),
                station.losUp ?: error("${station.id} LOS up missing")
            )
            requireUnitLos(los)
        }
    }
    val stationIds = input.stations.map { it.id }.toSet()
    input.observations.forEach { observation ->
        require(observation.stationId in stationIds) { "观测引用未知站点 ${observation.stationId}" }
        require(observation.covarianceGroup in input.covarianceGroups.map { it.code }.toSet()) {
            "观测引用未知协方差分组 ${observation.covarianceGroup}"
        }
    }
}

private fun DatasetInput.elevationPositiveConvention(): Boolean =
    crs.elevationPositive.equals("up", ignoreCase = true) &&
        crs.depthConvention.contains("positive downward", ignoreCase = true)
