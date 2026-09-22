package app

import kotlinx.serialization.Serializable

/**
 * 正演引擎：给定参数，在所有观测点计算标量预测
 *   pred_i = l_i · u3D_i
 * 其中 l_i 为 GNSS 基向量或归一化 InSAR LOS 向量。
 *
 * 模型输出的版本信息（engineVersion / coordVersion / 约定）随每个预测值
 * 一起记录，保证“任一预测值都能追溯到几何和坐标变换版本”。
 */
class ForwardEngine(val type: ModelType) {
    val model: DeformationModel = when (type) {
        ModelType.MOGI -> MogiModel()
        ModelType.DIKE -> DikeModel()
        ModelType.PLANE -> PlaneGridModel()
    }

    val engineVersion: String = "${Conventions.ENGINE_VERSION}:${type.name}"

    fun predict3(site: Site, p: SourceParams): Vec3 = model.displacement(site, p)

    fun predict(observations: List<Observation>, p: SourceParams): DoubleArray {
        val cache = HashMap<String, Vec3>()
        return DoubleArray(observations.size) { i ->
            val o = observations[i]
            val u3 = cache.getOrPut(o.siteId) {
                predict3(Site(o.siteId, o.x, o.y), p)
            }
            u3.dot(o.losOrProjection())
        }
    }
}

@Serializable
data class PredictionRecord(
    val observationId: String,
    val observed: Double,
    val predicted: Double,
    val residual: Double,
    val type: String,
    val epoch: String,
    val region: String,
    val x: Double,
    val y: Double,
    val losE: Double?,
    val losN: Double?,
    val losU: Double?,
    val engineVersion: String,
    val coordVersion: String,
    val transformVersion: String,
    val depthSignConvention: String,
    val params: Map<String, Double>,
)
