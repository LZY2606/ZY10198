package app

import kotlinx.serialization.Serializable

@Serializable
data class RunRequest(
    val datasetId: Long,
    val modelType: String,
    val regLambda: Double = 0.0,
    val seed: Long = 20260922L,
    val starts: Int = 24,
    val maxIter: Int = 400,
    val bounds: PhysicalBounds = PhysicalBounds(),
)

@Serializable
data class RunResult(
    val runId: Long,
    val type: ModelType,
    val solutions: List<SolutionOutput>,
    val startCount: Int,
)

@Serializable
data class SolutionOutput(
    val solutionId: Long,
    val rank: Int,
    val startIndex: Int,
    val params: Map<String, Double>,
    val chi2: Double,
    val reducedChi2: Double,
    val objective: Double,
    val bic: Double,
    val converged: Boolean,
    val iterations: Int,
    val activeBounds: ActiveBounds,
    val residualByType: Map<String, ResidualStats>,
    val residualByRegion: Map<String, ResidualStats>,
)

@Serializable
data class ResidualStats(
    val count: Int,
    val rms: Double,
    val mean: Double,
    val weightedRss: Double,
)

class AnalysisService(private val repo: Repository) {

    fun run(req: RunRequest): RunResult {
        val type = ModelType.valueOf(req.modelType)
        val observations = repo.observations(req.datasetId)
        val groups = repo.groups(req.datasetId)
        require(observations.isNotEmpty()) { "数据集为空，无法反演" }
        requireLosNormalized(observations)

        val cov = CovarianceStructure.build(observations, groups)
        val reg = Regularization(req.regLambda)
        val opt = MultiStartOptimizer(type, req.bounds, observations, cov, reg)

        val starts = opt.run(req.seed, req.starts, req.maxIter)
        val runId = repo.insertRun(
            req.datasetId, type, req.regLambda, req.seed,
            req.starts, req.maxIter, req.bounds,
        )
        starts.forEach { repo.insertStart(runId, it) }

        val engine = ForwardEngine(type)
        val dataset = repo.getDataset(req.datasetId)!!
        val outputs = starts.mapIndexed { rank, sr ->
            val params = SourceParams.fromArray(type, sr.params)
            val pred = engine.predict(observations, params)
            val r = DoubleArray(observations.size) { observations[it].value - pred[it] }
            val chi2 = cov.quadratic(r)
            val active = opt.activeBounds(sr.params)
            val records = observations.mapIndexed { i, o ->
                val v3 = engine.predict3(Site(o.siteId, o.x, o.y), params)
                PredictionRecord(
                    observationId = o.id, observed = o.value,
                    predicted = pred[i], residual = r[i],
                    type = o.type, epoch = o.epoch, region = o.region,
                    x = o.x, y = o.y, losE = o.losE, losN = o.losN, losU = o.losU,
                    engineVersion = engine.engineVersion,
                    coordVersion = Conventions.COORD_VERSION,
                    transformVersion = dataset.transformVersion,
                    depthSignConvention = Conventions.DEPTH_SIGN,
                    params = req.bounds.specs(type).mapIndexed { i2, sp2 -> sp2.key to sr.params[i2] }.toMap(),
                )
            }
            val solId = repo.insertSolution(
                runId, rank + 1, sr, type, chi2, observations.size,
                active, observations, records,
            )
            SolutionOutput(
                solutionId = solId, rank = rank + 1,
                startIndex = sr.startIndex,
                params = req.bounds.specs(type).mapIndexed { i2, sp2 -> sp2.key to sr.params[i2] }.toMap(),
                chi2 = chi2, reducedChi2 = chi2 / (observations.size - sr.params.size),
                objective = sr.objective,
                bic = observations.size *
                    kotlin.math.ln(chi2 / observations.size) +
                    sr.params.size * kotlin.math.ln(observations.size.toDouble()),
                converged = sr.converged, iterations = sr.iterations,
                activeBounds = active,
                residualByType = stats(observations, r, pred) { it.type },
                residualByRegion = stats(observations, r, pred) { it.region },
            )
        }
        return RunResult(runId, type, outputs, starts.size)
    }

    private fun stats(
        obs: List<Observation>, r: DoubleArray, pred: DoubleArray,
        key: (Observation) -> String,
    ): Map<String, ResidualStats> {
        return obs.indices.groupBy { idx -> key(obs[idx]) }.mapValues { (_, idx) ->
            val rr = idx.map { r[it] }
            val mean = rr.average()
            val rms = sqrt(rr.sumOf { it * it } / rr.size)
            val wrss = rr.sumOf { it * it }
            ResidualStats(rr.size, rms, mean, wrss)
        }
    }
}

private fun sqrt(x: Double) = kotlin.math.sqrt(x)
