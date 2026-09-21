package app

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun servesPageFitsModelsExportsAndReimports() = testApplication {
        val dbPath = Files.createTempFile("volcano-api", ".db")
        Files.delete(dbPath)
        application { configureRouting(AppDatabase(dbPath.toString()).also { it.migrate(); it.seedIfEmpty() }) }
        client.get("/").apply {
            assertEquals(HttpStatusCode.OK, status)
            assertTrue(bodyAsText().contains("火山形变解译台"))
        }
        client.post("/api/admin/reimport").apply { assertEquals(HttpStatusCode.OK, status) }

        val dike = fit(client, "DIKE")
        val point = fit(client, "POINT")
        val dikeId = dike.first
        val pointId = point.first
        val pointBody = point.second
        assertTrue(json.parseToJsonElement(pointBody).jsonObject.getValue("boundaryActive").jsonPrimitive.boolean)
        assertTrue(pointBody.contains("depthKm"))

        val compare = client.get("/api/compare?a=$pointId&b=$dikeId")
        assertEquals(HttpStatusCode.OK, compare.status)
        val compareBody = compare.bodyAsText()
        assertTrue(compareBody.contains("differencesByObservationType"))
        assertTrue(compareBody.contains("differencesByRegion"))

        val exported = client.get("/api/runs/$pointId/export").bodyAsText()
        val exportedJson = json.parseToJsonElement(exported).jsonObject
        assertTrue(exportedJson.getValue("predictions").jsonArray.isNotEmpty())
        assertTrue(exported.contains(GEOMETRY_VERSION))
        assertTrue(exported.contains(COORDINATE_TRANSFORM_VERSION))
    }

    private suspend fun fit(client: HttpClient, type: String): Pair<Long, String> {
        val response = client.post("/api/fit") {
            contentType(ContentType.Application.Json)
            setBody("""{"modelType":"$type","regularization":0.02,"starts":12,"bounds":{"maxDepthKm":8.0,"maxSizeKm":6.0}}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val id = json.parseToJsonElement(body).jsonObject.getValue("runId").jsonPrimitive.content.toLong()
        return id to body
    }
}
