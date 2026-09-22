package app

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ApiTest {

    @Test
    fun `index shows title and run endpoint returns boundary metadata`(@TempDir tmp: Path) {
        val repo = Repository(Database(tmp.resolve("api.sqlite").toFile().absolutePath))
        repo.importBundle(Fixture.bundle())
        val server = WebServer(repo, 0)
        testApplication {
            with(server) { application { studioModule(repo, null) } }
            client.get("/").let {
                assertEquals(HttpStatusCode.OK, it.status)
                assertTrue(it.bodyAsText().contains("火山形变解译台"))
            }
            val conv = client.get("/api/conventions").bodyAsText()
            assertTrue(conv.contains("ENU-LOCAL-v1"))
            assertTrue(conv.contains("downward"))

            val runResp = client.post("/api/runs") {
                setBody(
                    """{"datasetId":1,"modelType":"MOGI","regLambda":0,"seed":7,
                       |"starts":8,"maxIter":200,"bounds":{"depthMin":0.05,"depthMax":9}}""".trimMargin(),
                )
            }
            assertEquals(HttpStatusCode.OK, runResp.status, runResp.bodyAsText())
            val body = runResp.bodyAsText()
            assertTrue(body.contains("\"activeBounds\""))
            assertTrue(body.contains("\"chi2\""))
            assertTrue(body.contains("\"depthAtUpper\""))
        }
    }

    @Test
    fun `plane run reports depth upper boundary active and predictions are traceable`(@TempDir tmp: Path) {
        val repo = Repository(Database(tmp.resolve("plane.sqlite").toFile().absolutePath))
        repo.importBundle(Fixture.bundle())
        val server = WebServer(repo, 0)
        testApplication {
            with(server) { application { studioModule(repo, null) } }
            val runResp = client.post("/api/runs") {
                setBody(
                    """{"datasetId":1,"modelType":"PLANE","regLambda":0,"seed":20260922,
                       |"starts":16,"maxIter":300,
                       |"bounds":{"depthMin":0.05,"depthMax":6,"dipMin":0,"dipMax":40,
                       |"lengthMin":0.2,"lengthMax":10,"widthMin":0.2,"widthMax":10}}""".trimMargin(),
                )
            }
            val body = runResp.bodyAsText()
            assertEquals(HttpStatusCode.OK, runResp.status, body)
            assertTrue(body.contains("\"depthAtUpper\":true"), body.take(600))
            val solId = Regex("\"solutionId\":(\\d+)").find(body)!!.groupValues[1]
            val preds = client.get("/api/solutions/$solId/predictions").bodyAsText()
            assertTrue(preds.contains("engineVersion"))
            assertTrue(preds.contains("depthSignConvention"))
        }
    }

    @Test
    fun `unnormalized LOS is rejected`(@TempDir tmp: Path) {
        val repo = Repository(Database(tmp.resolve("bad.sqlite").toFile().absolutePath))
        val bundle = Fixture.bundle()
        val badObs = bundle.observations.toMutableList()
        val idx = badObs.indexOfFirst { it.type == "insar_los" }
        badObs[idx] = badObs[idx].copy(losE = 0.1, losN = 0.1, losU = 0.1)
        var thrown = false
        try {
            repo.importBundle(bundle.copy(observations = badObs))
        } catch (e: LosNormalizationException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
