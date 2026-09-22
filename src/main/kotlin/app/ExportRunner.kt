package app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

object ExportRunner {
    private val outJson = kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true }

    fun runJson(repo: Repository, runId: Long): String {
        val runRow = findRun(repo, runId)
        val solutions = repo.solutionsForRun(runId)
        val starts = repo.startPointsForRun(runId)
        val root = buildJsonObject {
            put("runId", runRow.id)
            put("datasetId", runRow.datasetId)
            put("modelType", runRow.type.name)
            put("regLambda", runRow.regLambda)
            put("seed", runRow.seed)
            put("starts", runRow.starts)
            put("maxIter", runRow.maxIter)
            put("createdAt", runRow.createdAt)
            put("bounds", json.parseToJsonElement(runRow.boundsJson))
            put("engineVersion", Conventions.ENGINE_VERSION)
            put("coordVersion", Conventions.COORD_VERSION)
            put("depthSignConvention", Conventions.DEPTH_SIGN)
            put("losTolerance", Conventions.LOS_TOLERANCE)
            put(
                "solutions",
                JsonArray(
                    solutions.map { outJson.encodeToJsonElement(it) },
                ),
            )
            put(
                "startPoints",
                JsonArray(
                    starts.map {
                        buildJsonObject {
                            put("startIndex", it.startIndex)
                            put("objective", it.objective)
                            put("iterations", it.iterations)
                            put("converged", it.converged)
                            put("params", json.parseToJsonElement(json.encodeToString(it.params)))
                        }
                    },
                ),
            )
        }
        return outJson.encodeToString(JsonObject.serializer(), root)
    }

    fun csv(repo: Repository, solutionId: Long): String {
        val records = repo.predictions(solutionId)
        val sb = StringBuilder()
        sb.append("# engine_version=${records.firstOrNull()?.engineVersion}\n")
        sb.append("# coord_version=${records.firstOrNull()?.coordVersion}\n")
        sb.append("# transform_version=${records.firstOrNull()?.transformVersion}\n")
        sb.append("# depth_sign=${records.firstOrNull()?.depthSignConvention}\n")
        sb.append("observation_id,type,epoch,region,x_km,y_km,observed_m,predicted_m,residual_m\n")
        for (r in records) {
            sb.append(
                listOf(
                    r.observationId, r.type, r.epoch, r.region, r.x, r.y,
                    r.observed, r.predicted, r.residual,
                ).joinToString(",") { it.toString() },
            ).append("\n")
        }
        return sb.toString()
    }

    private fun findRun(repo: Repository, runId: Long): RunSummary =
        repo.db.connection.createStatement().executeQuery("SELECT * FROM runs WHERE id=$runId").use { rs ->
            require(rs.next()) { "运行不存在: $runId" }
            RunSummary(
                rs.getLong("id"), rs.getLong("dataset_id"),
                ModelType.valueOf(rs.getString("model_type")),
                rs.getDouble("reg_lambda"), rs.getLong("seed"), rs.getInt("starts"),
                rs.getInt("max_iter"), rs.getString("bounds_json"), rs.getString("created_at"),
            )
        }
}
