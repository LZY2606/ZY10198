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
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelineContext
import kotlinx.serialization.json.Json

fun startServer(port: Int, databasePath: String) {
    val database = AppDatabase(databasePath)
    database.migrate()
    database.seedIfEmpty()
    embeddedServer(Netty, port = port, host = "127.0.0.1") {
        configureRouting(database)
    }.start(wait = true)
}

fun Application.configureRouting(database: AppDatabase) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    install(ContentNegotiation) { json(json) }
    val service = DeformationService(database)
    routing {
        get("/") {
            runApi {
                val html = javaClass.getResourceAsStream("/web/index.html")?.bufferedReader()?.use { it.readText() }
                    ?: error("index.html missing")
                call.respondText(html, ContentType.Text.Html)
            }
        }
        get("/health") {
            call.respondText("""{"status":"ok","name":"火山形变解译台"}""", ContentType.Application.Json)
        }
        get("/api/dataset") { runApi {
            val payload = database.latestPayload() ?: throw IllegalArgumentException("数据库为空")
            call.respondText(payload, ContentType.Application.Json)
        }}
        post("/api/fit") {
            runApi {
            val request = call.receive<FitRequest>()
            val (response, _) = service.fit(request)
            call.respondText(json.encodeToString(FitResponse.serializer(), response), ContentType.Application.Json)
            }
        }
        get("/api/runs") { runApi {
            call.respondText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(RunSummary.serializer()), database.runSummaries()), ContentType.Application.Json)
        }}
        get("/api/runs/{id}") { runApi {
            val id = call.parameters["id"]!!.toLong()
            val response = database.runResponse(id) ?: throw NoSuchElementException("run $id not found")
            call.respondText(json.encodeToString(FitResponse.serializer(), response), ContentType.Application.Json)
        }}
        get("/api/runs/{id}/predictions") { runApi {
            val id = call.parameters["id"]!!.toLong()
            val predictions = database.predictions(id)
            call.respondText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PredictionPoint.serializer()), predictions), ContentType.Application.Json)
        }}
        get("/api/runs/{id}/export") { runApi {
            val id = call.parameters["id"]!!.toLong()
            val exported = database.exportRun(id) ?: throw NoSuchElementException("run $id not found")
            call.response.headers.append("Content-Disposition", "attachment; filename=\"run-$id.json\"")
            call.respondText(exported, ContentType.Application.Json)
        }}
        get("/api/compare") { runApi {
            val first = call.request.queryParameters["a"]?.toLong() ?: throw IllegalArgumentException("missing a")
            val second = call.request.queryParameters["b"]?.toLong() ?: throw IllegalArgumentException("missing b")
            val result = service.compare(first, second)
            call.respondText(json.encodeToString(CompareResponse.serializer(), result), ContentType.Application.Json)
        }}
        post("/api/admin/reimport") {
            runApi {
            database.replaceFixture()
            call.respondText("""{"status":"reimported"}""", ContentType.Application.Json)
            }
        }
    }
}

private suspend inline fun PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.runApi(block: () -> Unit) {
    try {
        block()
    } catch (cause: Exception) {
        call.respondText(
            """{"error":"${cause.message?.replace("\"", "\\\"")}"}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest
        )
    }
}
