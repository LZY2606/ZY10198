package app

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant

val json = Json { prettyPrint = false; encodeDefaults = true }

class Repository(val db: Database) {

    fun listDatasets(): List<Dataset> = db.connection.createStatement().use { st ->
        st.executeQuery("SELECT * FROM datasets ORDER BY id").readAll {
            Dataset(
                id = it.getLong("id"),
                name = it.getString("name"),
                crs = it.getString("crs"),
                transformVersion = it.getString("transform_version"),
                originLat = it.getObject("origin_lat") as Double?,
                originLon = it.getObject("origin_lon") as Double?,
                importedAt = it.getString("imported_at"),
                sourceHash = it.getString("source_hash"),
                note = it.getString("note"),
            )
        }
    }

    fun getDataset(id: Long): Dataset? = listDatasets().firstOrNull { it.id == id }

    fun observations(datasetId: Long): List<Observation> =
        db.connection.prepareStatement("SELECT * FROM observations WHERE dataset_id=? ORDER BY id").use { ps ->
            ps.setLong(1, datasetId)
            ps.executeQuery().readAll { rs ->
                Observation(
                    id = rs.getString("id"),
                    siteId = rs.getString("site_id"),
                    type = rs.getString("obs_type"),
                    x = rs.getDouble("x"),
                    y = rs.getDouble("y"),
                    value = rs.getDouble("value"),
                    sigmaWhite = rs.getDouble("sigma_white"),
                    groupId = rs.getString("group_id"),
                    epoch = rs.getString("epoch"),
                    losE = rs.getObject("los_e") as Double?,
                    losN = rs.getObject("los_n") as Double?,
                    losU = rs.getObject("los_u") as Double?,
                    region = rs.getString("region"),
                )
            }
        }

    fun groups(datasetId: Long): List<CovarianceGroup> =
        db.connection.prepareStatement("SELECT * FROM cov_groups WHERE dataset_id=? ORDER BY id").use { ps ->
            ps.setLong(1, datasetId)
            ps.executeQuery().readAll {
                CovarianceGroup(
                    id = it.getString("id"),
                    label = it.getString("label"),
                    obsType = it.getString("obs_type"),
                    epoch = it.getString("epoch"),
                    sigmaCme = it.getDouble("sigma_cme"),
                )
            }
        }

    fun importBundle(bundle: ImportBundle): Long {
        requireLosNormalized(bundle.observations)
        validateGroups(bundle)
        val conn = db.connection
        val dsId: Long
        conn.prepareStatement(
            """INSERT INTO datasets(name,crs,transform_version,origin_lat,origin_lon,
               imported_at,source_hash,note) VALUES(?,?,?,?,?,?,?,?)""",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setString(1, bundle.dataset.name)
            ps.setString(2, bundle.dataset.crs)
            ps.setString(3, bundle.dataset.transformVersion)
            ps.setObject(4, bundle.dataset.originLat)
            ps.setObject(5, bundle.dataset.originLon)
            ps.setString(6, Instant.now().toString())
            ps.setString(7, bundle.dataset.sourceHash)
            ps.setString(8, bundle.dataset.note)
            ps.executeUpdate()
            dsId = ps.generatedKeys.getLong(1)
        }
        conn.prepareStatement(
            """INSERT INTO cov_groups(id,dataset_id,label,obs_type,epoch,sigma_cme)
               VALUES(?,?,?,?,?,?)""",
        ).use { ps ->
            for (g in bundle.groups) {
                ps.setString(1, g.id); ps.setLong(2, dsId); ps.setString(3, g.label)
                ps.setString(4, g.obsType); ps.setString(5, g.epoch); ps.setDouble(6, g.sigmaCme)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.prepareStatement(
            """INSERT INTO observations(id,dataset_id,site_id,obs_type,x,y,value,
               sigma_white,group_id,epoch,los_e,los_n,los_u,region)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        ).use { ps ->
            for (o in bundle.observations) {
                ps.setString(1, o.id); ps.setLong(2, dsId); ps.setString(3, o.siteId)
                ps.setString(4, o.type); ps.setDouble(5, o.x); ps.setDouble(6, o.y)
                ps.setDouble(7, o.value); ps.setDouble(8, o.sigmaWhite)
                ps.setString(9, o.groupId); ps.setString(10, o.epoch)
                ps.setObject(11, o.losE); ps.setObject(12, o.losN); ps.setObject(13, o.losU)
                ps.setString(14, o.region)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        return dsId
    }

    private fun validateGroups(bundle: ImportBundle) {
        val byId = bundle.groups.associateBy { it.id }
        for (o in bundle.observations) {
            require(byId.containsKey(o.groupId)) { "观测 ${o.id} 引用了不存在的协方差分组 ${o.groupId}" }
            require(o.sigmaWhite > 0) { "观测 ${o.id} 的白噪声 sigma 必须为正" }
            require(byId.getValue(o.groupId).sigmaCme >= 0) { "共同模式误差标准差必须非负" }
            if (o.obsType() == ObsType.INSAR_LOS) {
                require(o.losE != null && o.losN != null && o.losU != null)
                { "InSAR 观测 ${o.id} 缺少 LOS 单位向量" }
            }
        }
    }

    fun wipeAll() {
        db.connection.createStatement().use { st ->
            for (t in listOf("predictions", "start_points", "solutions", "runs",
                "observations", "cov_groups", "datasets")) {
                st.executeUpdate("DELETE FROM $t")
            }
        }
    }

    fun insertRun(
        datasetId: Long, type: ModelType, lambda: Double, seed: Long,
        starts: Int, maxIter: Int, bounds: PhysicalBounds,
    ): Long {
        return db.connection.prepareStatement(
            """INSERT INTO runs(dataset_id,model_type,reg_lambda,seed,starts,max_iter,
               bounds_json,created_at,engine_version,coord_version)
               VALUES(?,?,?,?,?,?,?,?,?,?)""",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setLong(1, datasetId); ps.setString(2, type.name); ps.setDouble(3, lambda)
            ps.setLong(4, seed); ps.setInt(5, starts); ps.setInt(6, maxIter)
            ps.setString(7, json.encodeToString(bounds))
            ps.setString(8, Instant.now().toString())
            ps.setString(9, Conventions.ENGINE_VERSION)
            ps.setString(10, Conventions.COORD_VERSION)
            ps.executeUpdate()
            ps.generatedKeys.getLong(1)
        }
    }

    fun insertStart(runId: Long, sr: StartResult) {
        db.connection.prepareStatement(
            """INSERT INTO start_points(run_id,start_index,params_json,objective,
               iterations,converged) VALUES(?,?,?,?,?,?)""",
        ).use { ps ->
            ps.setLong(1, runId); ps.setInt(2, sr.startIndex)
            ps.setString(3, json.encodeToString(sr.params.toList()))
            ps.setDouble(4, sr.objective); ps.setInt(5, sr.iterations)
            ps.setInt(6, if (sr.converged) 1 else 0)
            ps.executeUpdate()
        }
    }

    fun insertSolution(
        runId: Long, rank: Int, sr: StartResult, type: ModelType,
        chi2: Double, nObs: Int, active: ActiveBounds,
        observations: List<Observation>, records: List<PredictionRecord>,
    ): Long {
        val solId = db.connection.prepareStatement(
            """INSERT INTO solutions(run_id,start_index,rank,objective,chi2,n_obs,
               n_params,bic,converged,iterations,active_bounds,depth_at_upper,
               depth_at_lower,size_at_upper,active_detail,params_json)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            val nPar = sr.params.size
            // 独立报告各模型自身的 BIC；跨模型不做综合分数压扁
            val bic = nObs * kotlin.math.ln(chi2 / nObs.coerceAtLeast(1)) + nPar * kotlin.math.ln(nObs.toDouble())
            ps.setLong(1, runId); ps.setInt(2, sr.startIndex); ps.setInt(3, rank)
            ps.setDouble(4, sr.objective); ps.setDouble(5, chi2); ps.setInt(6, nObs)
            ps.setInt(7, nPar); ps.setDouble(8, bic)
            ps.setInt(9, if (sr.converged) 1 else 0); ps.setInt(10, sr.iterations)
            ps.setInt(11, if (active.active) 1 else 0)
            ps.setInt(12, if (active.depthAtUpper) 1 else 0)
            ps.setInt(13, if (active.depthAtLower) 1 else 0)
            ps.setString(14, json.encodeToString(active.sizeAtUpper))
            ps.setString(15, json.encodeToString(active.details))
            ps.setString(16, json.encodeToString(sr.params.toList()))
            ps.executeUpdate()
            ps.generatedKeys.getLong(1)
        }
        db.connection.prepareStatement(
            """INSERT INTO predictions(solution_id,observation_id,observed,predicted,
               residual,obs_type,epoch,region,x,y,los_e,los_n,los_u,engine_version,
               coord_version,transform_version,depth_sign,params_json)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        ).use { ps ->
            for (r in records) {
                ps.setLong(1, solId); ps.setString(2, r.observationId)
                ps.setDouble(3, r.observed); ps.setDouble(4, r.predicted)
                ps.setDouble(5, r.residual); ps.setString(6, r.type)
                ps.setString(7, r.epoch); ps.setString(8, r.region)
                ps.setDouble(9, r.x); ps.setDouble(10, r.y)
                ps.setObject(11, r.losE); ps.setObject(12, r.losN); ps.setObject(13, r.losU)
                ps.setString(14, r.engineVersion); ps.setString(15, r.coordVersion)
                ps.setString(16, r.transformVersion); ps.setString(17, r.depthSignConvention)
                ps.setString(18, json.encodeToString(r.params))
                ps.addBatch()
            }
            ps.executeBatch()
        }
        return solId
    }

    fun listRuns(datasetId: Long): List<RunSummary> =
        db.connection.prepareStatement("SELECT * FROM runs WHERE dataset_id=? ORDER BY id").use { ps ->
            ps.setLong(1, datasetId)
            ps.executeQuery().readAll {
                RunSummary(
                    it.getLong("id"), it.getLong("dataset_id"),
                    ModelType.valueOf(it.getString("model_type")),
                    it.getDouble("reg_lambda"), it.getLong("seed"),
                    it.getInt("starts"), it.getInt("max_iter"),
                    it.getString("bounds_json"), it.getString("created_at"),
                )
            }
        }

    fun solutionsForRun(runId: Long): List<SolutionRow> =
        db.connection.prepareStatement("SELECT * FROM solutions WHERE run_id=? ORDER BY rank").use { ps ->
            ps.setLong(1, runId)
            ps.executeQuery().readAll { rs ->
                SolutionRow(
                    id = rs.getLong("id"),
                    runId = runId,
                    startIndex = rs.getInt("start_index"),
                    rank = rs.getInt("rank"),
                    objective = rs.getDouble("objective"),
                    chi2 = rs.getDouble("chi2"),
                    nObs = rs.getInt("n_obs"),
                    nParams = rs.getInt("n_params"),
                    bic = rs.getDouble("bic"),
                    converged = rs.getInt("converged") == 1,
                    activeBounds = rs.getInt("active_bounds") == 1,
                    depthAtUpper = rs.getInt("depth_at_upper") == 1,
                    depthAtLower = rs.getInt("depth_at_lower") == 1,
                    sizeAtUpper = json.decodeFromString<List<String>>(rs.getString("size_at_upper")),
                    activeDetail = json.decodeFromString<List<String>>(rs.getString("active_detail")),
                    params = json.decodeFromString<List<Double>>(rs.getString("params_json")),
                )
            }
        }

    fun startPointsForRun(runId: Long): List<StartPointRow> =
        db.connection.prepareStatement("SELECT * FROM start_points WHERE run_id=? ORDER BY start_index").use { ps ->
            ps.setLong(1, runId)
            ps.executeQuery().readAll {
                StartPointRow(
                    it.getInt("start_index"),
                    json.decodeFromString<List<Double>>(it.getString("params_json")),
                    it.getDouble("objective"), it.getInt("iterations"),
                    it.getInt("converged") == 1,
                )
            }
        }

    fun predictions(solutionId: Long): List<PredictionRecord> =
        db.connection.prepareStatement("SELECT * FROM predictions WHERE solution_id=? ORDER BY id").use { ps ->
            ps.setLong(1, solutionId)
            ps.executeQuery().readAll {
                PredictionRecord(
                    observationId = it.getString("observation_id"),
                    observed = it.getDouble("observed"),
                    predicted = it.getDouble("predicted"),
                    residual = it.getDouble("residual"),
                    type = it.getString("obs_type"),
                    epoch = it.getString("epoch"),
                    region = it.getString("region"),
                    x = it.getDouble("x"), y = it.getDouble("y"),
                    losE = it.getObject("los_e") as Double?,
                    losN = it.getObject("los_n") as Double?,
                    losU = it.getObject("los_u") as Double?,
                    engineVersion = it.getString("engine_version"),
                    coordVersion = it.getString("coord_version"),
                    transformVersion = it.getString("transform_version"),
                    depthSignConvention = it.getString("depth_sign"),
                    params = json.decodeFromString<Map<String, Double>>(it.getString("params_json")),
                )
            }
        }
}

@Serializable
data class RunSummary(
    val id: Long, val datasetId: Long, val type: ModelType,
    val regLambda: Double, val seed: Long, val starts: Int, val maxIter: Int,
    val boundsJson: String, val createdAt: String,
)

@Serializable
data class SolutionRow(
    val id: Long, val runId: Long, val startIndex: Int, val rank: Int,
    val objective: Double, val chi2: Double, val nObs: Int, val nParams: Int,
    val bic: Double, val converged: Boolean,
    val activeBounds: Boolean, val depthAtUpper: Boolean, val depthAtLower: Boolean,
    val sizeAtUpper: List<String>, val activeDetail: List<String>, val params: List<Double>,
)

@Serializable
data class StartPointRow(
    val startIndex: Int, val params: List<Double>, val objective: Double,
    val iterations: Int, val converged: Boolean,
)

private inline fun <T> java.sql.ResultSet.readAll(mapper: (java.sql.ResultSet) -> T): List<T> {
    val out = mutableListOf<T>()
    while (next()) out.add(mapper(this))
    return out
}

fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") {
        "%02x".format(it)
    }
