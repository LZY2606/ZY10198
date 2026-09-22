package app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class PersistenceTest {

    private fun freshDb(f: File): Repository {
        if (f.exists()) f.delete()
        return Repository(Database(f.absolutePath))
    }

    @Test
    fun `csv roundtrip preserves crs los groups epochs and hash`(@TempDir tmp: Path) {
        val bundle = Fixture.bundle()
        val csv = CsvImport.render(bundle)
        val reparsed = CsvImport.parse(csv)
        assertEquals(bundle.observations.size, reparsed.observations.size)
        assertEquals(bundle.groups.size, reparsed.groups.size)
        assertEquals(bundle.dataset.crs, reparsed.dataset.crs)
        assertEquals(bundle.dataset.transformVersion, reparsed.dataset.transformVersion)
        requireLosNormalized(reparsed.observations)
        // LOS 数值、分组、时段保留
        val origInsar = bundle.observations.first { it.type == "insar_los" }
        val newInsar = reparsed.observations.first { it.id == origInsar.id }
        assertEquals(origInsar.losE, newInsar.losE)
        assertEquals(origInsar.groupId, newInsar.groupId)
        assertEquals(origInsar.epoch, newInsar.epoch)
        assertEquals(sha256(csv), reparsed.dataset.sourceHash)
    }

    @Test
    fun `wipe import rerun gives identical best solution`(@TempDir tmp: Path) {
        val dbFile = tmp.resolve("t.sqlite").toFile()
        val repo1 = freshDb(dbFile)
        val id1 = repo1.importBundle(Fixture.bundle())
        val svc1 = AnalysisService(repo1)
        val r1 = svc1.run(
            RunRequest(id1, "MOGI", 0.0, 7, 10, 250, PhysicalBounds(depthMax = 9.0)),
        )
        val best1 = r1.solutions.first()
        repo1.db.close()

        // 清空（删除文件模拟）后重新导入、同种子重放
        dbFile.delete()
        val repo2 = freshDb(dbFile)
        val id2 = repo2.importBundle(Fixture.bundle())
        val r2 = AnalysisService(repo2).run(
            RunRequest(id2, "MOGI", 0.0, 7, 10, 250, PhysicalBounds(depthMax = 9.0)),
        )
        val best2 = r2.solutions.first()
        assertEquals(best1.params, best2.params)
        assertEquals(best1.chi2, best2.chi2, 1e-9)
        assertEquals(best1.activeBounds.depthAtUpper, best2.activeBounds.depthAtUpper)
        // 多起点全部持久化
        assertEquals(10, repo2.startPointsForRun(r2.runId).size)
        repo2.db.close()
    }

    @Test
    fun `predictions carry provenance metadata`(@TempDir tmp: Path) {
        val repo = freshDb(tmp.resolve("p.sqlite").toFile())
        val id = repo.importBundle(Fixture.bundle())
        val r = AnalysisService(repo).run(
            RunRequest(id, "PLANE", 0.0, 1, 6, 120,
                PhysicalBounds(depthMax = 6.0, dipMax = 40.0, lengthMax = 10.0, widthMax = 10.0)),
        )
        val records = repo.predictions(r.solutions.first().solutionId)
        assertTrue(records.isNotEmpty())
        val one = records.first()
        assertTrue(one.engineVersion.contains("PLANE"))
        assertEquals(Conventions.COORD_VERSION, one.coordVersion)
        assertTrue(one.depthSignConvention.contains("downward"))
        assertTrue(one.params.containsKey("depth"))
    }
}
