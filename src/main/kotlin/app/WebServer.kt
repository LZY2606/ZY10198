package app

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json as KxJson
import java.io.File

@Serializable
data class ImportResult(val datasetId: Long, val summary: DatasetSummary)

@Serializable
data class ObservationsBundle(val observations: List<Observation>, val groups: List<CovarianceGroup>)

@Serializable
data class RunDetail(
    val run: RunSummary,
    val solutions: List<SolutionRow>,
    val starts: List<StartPointRow>,
)

@Serializable
data class GridResponse(val solutionId: Long, val grid: List<GridPoint>)

@Serializable
data class ErrorResponse(val error: String, val detail: String? = null)

@Serializable
data class DatasetSummary(
    val id: Long, val name: String, val crs: String,
    val transformVersion: String, val originLat: Double?, val originLon: Double?,
    val importedAt: String, val sourceHash: String, val note: String,
    val nObs: Int, val nGnss: Int, val nInsar: Int, val nGroups: Int,
    val epochs: List<String>, val regions: List<String>,
    val losMaxDeviation: Double,
    val conventions: ConventionInfo,
)

@Serializable
data class ConventionInfo(
    val engineVersion: String,
    val coordVersion: String,
    val depthSign: String,
    val losTolerance: Double,
    val poissonRatio: Double,
)

fun conventionsInfo() = ConventionInfo(
    Conventions.ENGINE_VERSION, Conventions.COORD_VERSION,
    Conventions.DEPTH_SIGN, Conventions.LOS_TOLERANCE, Conventions.POISSON_RATIO,
)

class WebServer(
    private val repo: Repository,
    private val port: Int,
    private val webRoot: File? = null,
) {
    fun start() {
        embeddedServer(Netty, port = port, host = "127.0.0.1") {
            studioModule(repo, webRoot)
        }.start(wait = true)
    }

    fun Application.studioModule(repo: Repository, webRoot: File?) {
            install(ContentNegotiation) { json(KxJson { encodeDefaults = true; ignoreUnknownKeys = true }) }
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(cause.javaClass.simpleName, cause.message),
                    )
                }
            }
            routing {
                get("/") {
                    val html = (webRoot?.resolve("index.html")?.takeIf { it.exists()}
                        ?: File("src/main/resources/web/index.html").takeIf { it.exists() }
                        ?: File("web/index.html"))
                    call.respondText(html.readText(), ContentType.Text.Html)
                }
                get("/app.js") {
                    val f = (webRoot?.resolve("app.js")?.takeIf { it.exists() }
                        ?: File("src/main/resources/web/app.js").takeIf { it.exists() }
                        ?: File("web/app.js"))
                    call.respondText(f.readText(), ContentType.Application.JavaScript)
                }
                get("/api/conventions") { call.respond(conventionsInfo()) }

                get("/api/datasets") {
                    call.respond(repo.listDatasets().map { summarize(it.id) })
                }
                post("/api/datasets/import-fixture") {
                    val bundle = Fixture.bundle()
                    val id = repo.importBundle(bundle)
                    call.respond(ImportResult(id, summarize(id)))
                }
                post("/api/datasets/import-csv") {
                    val text = call.receiveText()
                    val bundle = CsvImport.parse(text)
                    val id = repo.importBundle(bundle)
                    call.respond(ImportResult(id, summarize(id)))
                }
                get("/api/datasets/{id}") {
                    val id = call.parameters["id"]!!.toLong()
                    call.respond(summarize(id))
                }
                get("/api/datasets/{id}/observations") {
                    val id = call.parameters["id"]!!.toLong()
                    call.respond(ObservationsBundle(repo.observations(id), repo.groups(id)))
                }
                post("/api/admin/wipe") {
                    repo.wipeAll()
                    call.respond(mapOf("ok" to "true"))
                }

                post("/api/runs") {
                    val req = json.decodeFromString<RunRequest>(call.receiveText())
                    val result = AnalysisService(repo).run(req)
                    call.respond(result)
                }
                get("/api/datasets/{id}/runs") {
                    val id = call.parameters["id"]!!.toLong()
                    call.respond(repo.listRuns(id))
                }
                get("/api/runs/{id}") {
                    val id = call.parameters["id"]!!.toLong()
                    call.respond(RunDetail(runSummary(id), repo.solutionsForRun(id), repo.startPointsForRun(id)))
                }
                get("/api/solutions/{id}/predictions") {
                    val id = call.parameters["id"]!!.toLong()
                    call.respond(repo.predictions(id))
                }
                post("/api/compare") {
                    val req = json.decodeFromString<CompareRequest>(call.receiveText())
                    call.respond(compare(req))
                }
                get("/api/solutions/{id}/export") {
                    val id = call.parameters["id"]!!.toLong()
                    call.respondText(ExportRunner.csv(repo, id), ContentType.Text.CSV)
                }
                get("/api/runs/{id}/export") {
                    val id = call.parameters["id"]!!.toLong()
                    call.response.headers.append(
                        io.ktor.http.HttpHeaders.ContentDisposition,
                        "attachment; filename=run-$id.json",
                    )
                    call.respondText(ExportRunner.runJson(repo, id), ContentType.Application.Json)
                }
                get("/api/grid") {
                    val q = call.request.queryParameters
                    val solId = q["solutionId"]!!.toLong()
                    val type = ModelType.valueOf(q["modelType"]!!)
                    val params = q["params"]!!.split(";").map { it.toDouble() }.toDoubleArray()
                    val p = SourceParams.fromArray(type, params)
                    val span = (q["span"] ?: "10.0").toDouble()
                    val step = (q["step"] ?: "1.0").toDouble()
                    val grid = GridRenderer.render(type, p, span, step)
                    call.respond(GridResponse(solId, grid))
                }
            }
    }

    private fun summarize(id: Long): DatasetSummary {
        val d = repo.getDataset(id) ?: error("数据集不存在: $id")
        val obs = repo.observations(id)
        val groups = repo.groups(id)
        return DatasetSummary(
            id = d.id, name = d.name, crs = d.crs,
            transformVersion = d.transformVersion, originLat = d.originLat,
            originLon = d.originLon, importedAt = d.importedAt, sourceHash = d.sourceHash,
            note = d.note, nObs = obs.size,
            nGnss = obs.count { it.obsType().isGnss },
            nInsar = obs.count { !it.obsType().isGnss },
            nGroups = groups.size,
            epochs = obs.map { it.epoch }.distinct().sorted(),
            regions = obs.map { it.region }.distinct().sorted(),
            losMaxDeviation = losDeviations(obs).values.maxOrNull() ?: 0.0,
            conventions = conventionsInfo(),
        )
    }

    private fun runSummary(id: Long): RunSummary {
        // run 不绑定单一数据集查询便利；直接取任一
        val rs = repo.db.connection.createStatement().executeQuery("SELECT * FROM runs WHERE id=$id")
        if (!rs.next()) error("运行不存在: $id")
        return RunSummary(
            rs.getLong("id"), rs.getLong("dataset_id"),
            ModelType.valueOf(rs.getString("model_type")),
            rs.getDouble("reg_lambda"), rs.getLong("seed"), rs.getInt("starts"),
            rs.getInt("max_iter"), rs.getString("bounds_json"), rs.getString("created_at"),
        )
    }

    private fun compare(req: CompareRequest): CompareResponse {
        val a = repo.predictions(req.solutionA)
        val b = repo.predictions(req.solutionB)
        val byA = a.associateBy { it.observationId }
        val byB = b.associateBy { it.observationId }
        val ids = a.map { it.observationId }.intersect(b.map { it.observationId }.toSet())
        data class Acc(var n: Int = 0, var sumA: Double = 0.0, var sumB: Double = 0.0,
                      var ssA: Double = 0.0, var ssB: Double = 0.0, var ssDiff: Double = 0.0)
        val groups = HashMap<String, Acc>()
        for (id in ids) {
            val ra = byA.getValue(id); val rb = byB.getValue(id)
            val k = when (req.mode) {
                CompareMode.TYPE -> ra.type
                CompareMode.REGION -> ra.region
                CompareMode.EPOCH -> ra.epoch
            }
            if (!req.typesFilter.isNullOrEmpty() && ra.type !in req.typesFilter) continue
            if (!req.regionsFilter.isNullOrEmpty() && ra.region !in req.regionsFilter) continue
            val acc = groups.getOrPut(k) { Acc() }
            acc.n++; acc.sumA += ra.residual; acc.sumB += rb.residual
            acc.ssA += ra.residual * ra.residual
            acc.ssB += rb.residual * rb.residual
            acc.ssDiff += (ra.predicted - rb.predicted).let { it * it }
        }
        val stats = groups.map { (k, acc) ->
            CompareGroup(
                key = k, count = acc.n,
                rmsA = kotlin.math.sqrt(acc.ssA / acc.n),
                rmsB = kotlin.math.sqrt(acc.ssB / acc.n),
                meanResidualA = acc.sumA / acc.n,
                meanResidualB = acc.sumB / acc.n,
                rmsPredictionDifference = kotlin.math.sqrt(acc.ssDiff / acc.n),
            )
        }.sortedBy { it.key }
        val solA = a.first(); val solB = b.first()
        return CompareResponse(
            solutionA = req.solutionA, solutionB = req.solutionB,
            engineVersionA = solA.engineVersion, engineVersionB = solB.engineVersion,
            coordVersionA = solA.coordVersion, coordVersionB = solB.coordVersion,
            transformVersionA = solA.transformVersion, transformVersionB = solB.transformVersion,
            groups = stats,
        )
    }
}

@Serializable
enum class CompareMode { TYPE, REGION, EPOCH }

@Serializable
data class CompareRequest(
    val solutionA: Long,
    val solutionB: Long,
    val mode: CompareMode = CompareMode.TYPE,
    val typesFilter: List<String>? = null,
    val regionsFilter: List<String>? = null,
)

@Serializable
data class CompareGroup(
    val key: String, val count: Int,
    val rmsA: Double, val rmsB: Double,
    val meanResidualA: Double, val meanResidualB: Double,
    val rmsPredictionDifference: Double,
)

@Serializable
data class CompareResponse(
    val solutionA: Long, val solutionB: Long,
    val engineVersionA: String, val engineVersionB: String,
    val coordVersionA: String, val coordVersionB: String,
    val transformVersionA: String, val transformVersionB: String,
    val groups: List<CompareGroup>,
)
