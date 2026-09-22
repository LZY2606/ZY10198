package app

import kotlinx.serialization.Serializable

enum class ObsType(val code: String) {
    GNSS_E("gnss_e"), GNSS_N("gnss_n"), GNSS_U("gnss_u"), INSAR_LOS("insar_los");

    val isGnss: Boolean get() = this != INSAR_LOS
    val projection: Vec3
        get() = when (this) {
            GNSS_E -> Vec3(1.0, 0.0, 0.0)
            GNSS_N -> Vec3(0.0, 1.0, 0.0)
            GNSS_U -> Vec3(0.0, 0.0, 1.0)
            INSAR_LOS -> error("InSAR 使用每点独立 LOS 向量")
        }
}

/**
 * 协方差分组：同一组内的观测共享一个零均值“共同模式误差”(CME / common-mode
 * error) 随机变量，方差 sigma_cme^2；各组 CME 相互独立。
 *
 * 典型分组：
 *  - GNSS：按观测时段（session/epoch）成组，或按区域台网成组；
 *  - InSAR：按轨道/帧 (track/frame) 成组（整景共享一个大气/轨道残差）。
 *
 * 单观测总方差 = sigmaWhite^2 + sigmaCme^2；
 * 同组任意两观测协方差 = sigmaCme^2（显式相关，绝不通过重复抽样伪造独立）。
 */
@Serializable
data class CovarianceGroup(
    val id: String,
    val label: String,
    val obsType: String,
    val epoch: String,
    val sigmaCme: Double, // m
)

@Serializable
data class Observation(
    val id: String,
    val siteId: String,
    val type: String,              // ObsType.code
    val x: Double,                 // ENU km
    val y: Double,
    val value: Double,             // m
    val sigmaWhite: Double,        // m，独立白噪声
    val groupId: String,
    val epoch: String,
    val losE: Double? = null,      // 仅 InSAR
    val losN: Double? = null,
    val losU: Double? = null,
    val region: String = "default",
) {
    fun obsType(): ObsType = ObsType.entries.first { it.code == type }

    fun losOrProjection(): Vec3 = when (obsType()) {
        ObsType.INSAR_LOS -> Vec3(losE!!, losN!!, losU!!)
        else -> obsType().projection
    }

    fun losVec(): LosVec? = if (obsType() == ObsType.INSAR_LOS)
        LosVec(losE!!, losN!!, losU!!) else null
}

@Serializable
data class Dataset(
    val id: Long = 0,
    val name: String,
    val crs: String,
    val transformVersion: String,
    val originLat: Double? = null,
    val originLon: Double? = null,
    val importedAt: String,
    val sourceHash: String,
    val note: String = "",
)

data class ImportBundle(
    val dataset: Dataset,
    val observations: List<Observation>,
    val groups: List<CovarianceGroup>,
)

/** 归一化校验；返回每条 InSAR LOS 的 |l|-1 偏差（GNSS 恒为 0）。 */
fun losDeviations(obs: List<Observation>): Map<String, Double> =
    obs.filter { it.obsType() == ObsType.INSAR_LOS }
        .associate { it.id to (it.losVec()?.deviation ?: 0.0) }

class LosNormalizationException(val bad: Map<String, Double>) : RuntimeException(
    "视线向量未在容差 ${Conventions.LOS_TOLERANCE} 内归一：$bad",
)

fun requireLosNormalized(obs: List<Observation>) {
    val bad = losDeviations(obs).filterValues { it > Conventions.LOS_TOLERANCE }
    if (bad.isNotEmpty()) throw LosNormalizationException(bad)
}
