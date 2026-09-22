package app

/**
 * 观测 CSV 格式（UTF-8，逗号分隔，# 开头为元数据头）：
 *
 * # dataset: 演示火山 A
 * # crs: EPSG:32654
 * # transform: ENU-LOCAL-v1
 * # origin_lat: 35.40
 * # origin_lon: 138.70
 * # note: ...
 * # groups
 * # group,label,type,epoch,sigma_cme_m
 * G-E1,GNSS 第一时段,gnss,2024-05,0.006
 * T-A84,InSAR A84 升轨,insar,2024-05~07,0.012
 * # observations
 * # id,site,type,x_km,y_km,value_m,sigma_white_m,group,epoch,los_e,los_n,los_u,region
 * G01-E,G01,gnss_e, ...
 *
 * GNSS 行的 LOS 三列留空；InSAR 行必须给出归一化 LOS 单位向量
 * （|l|=1，容差 1e-6），导入时强制校验。
 */
object CsvImport {

    fun parse(content: String, nameHint: String = "imported"): ImportBundle {
        val meta = mutableMapOf<String, String>()
        val groups = mutableListOf<CovarianceGroup>()
        val obs = mutableListOf<Observation>()
        var section = "meta"

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("#")) {
                val body = line.removePrefix("#").trim()
                when {
                    body.startsWith("group", ignoreCase = true) &&
                        body.contains("sigma_cme", ignoreCase = true) -> section = "groups"
                    body.startsWith("id,") -> section = "obs"
                    body.startsWith("groups", ignoreCase = true) -> section = "groups"
                    body.startsWith("observations", ignoreCase = true) -> section = "observations"
                    else -> {
                        if (body.contains(",") || section == "groups" || section == "observations") {
                            when (section) {
                                "groups" -> parseGroup(body)?.let(groups::add)
                                "observations" -> parseObs(body)?.let(obs::add)
                            }
                        } else {
                            val idx = body.indexOf(":")
                            if (idx > 0) meta[body.substring(0, idx).trim().lowercase()] =
                                body.substring(idx + 1).trim()
                        }
                    }
                }
            } else {
                when (section) {
                    "groups" -> parseGroup(line)?.let(groups::add)
                    "obs", "observations" -> parseObs(line)?.let(obs::add)
                }
            }
        }

        val ds = Dataset(
            name = meta["dataset"] ?: nameHint,
            crs = meta["crs"] ?: "ENU-LOCAL",
            transformVersion = meta["transform"] ?: Conventions.COORD_VERSION,
            originLat = meta["origin_lat"]?.toDoubleOrNull(),
            originLon = meta["origin_lon"]?.toDoubleOrNull(),
            importedAt = "",
            sourceHash = sha256(content.replace("\r\n", "\n")),
            note = meta["note"] ?: "",
        )
        require(obs.isNotEmpty()) { "CSV 中没有观测行" }
        require(groups.isNotEmpty()) { "CSV 中没有协方差分组" }
        return ImportBundle(ds, obs, groups)
    }

    private fun parseGroup(line: String): CovarianceGroup? {
        val f = line.split(",").map { it.trim() }
        if (f.size < 5) return null
        if (f[0].equals("group", true)) return null
        return CovarianceGroup(
            id = f[0], label = f[1], obsType = f[2].lowercase(),
            epoch = f[3], sigmaCme = f[4].toDouble(),
        )
    }

    private fun parseObs(line: String): Observation? {
        val f = line.split(",").map { it.trim() }
        if (f.size < 10) return null
        if (f[0] == "id") return null
        val code = f[2].lowercase()
        return Observation(
            id = f[0], siteId = f[1], type = code,
            x = f[3].toDouble(), y = f[4].toDouble(),
            value = f[5].toDouble(), sigmaWhite = f[6].toDouble(),
            groupId = f[7], epoch = f[8],
            losE = f.getOrNull(9)?.takeIf { it.isNotEmpty() }?.toDouble(),
            losN = f.getOrNull(10)?.takeIf { it.isNotEmpty() }?.toDouble(),
            losU = f.getOrNull(11)?.takeIf { it.isNotEmpty() }?.toDouble(),
            region = f.getOrNull(12)?.takeIf { it.isNotEmpty() } ?: "default",
        )
    }

    fun render(bundle: ImportBundle): String {
        val d = bundle.dataset
        val sb = StringBuilder()
        sb.append("# dataset: ${d.name}\n")
        sb.append("# crs: ${d.crs}\n")
        sb.append("# transform: ${d.transformVersion}\n")
        d.originLat?.let { sb.append("# origin_lat: $it\n") }
        d.originLon?.let { sb.append("# origin_lon: $it\n") }
        if (d.note.isNotEmpty()) sb.append("# note: ${d.note}\n")
        sb.append("# groups\n")
        sb.append("# group,label,type,epoch,sigma_cme_m\n")
        for (g in bundle.groups) {
            sb.append("${g.id},${g.label},${g.obsType},${g.epoch},${g.sigmaCme}\n")
        }
        sb.append("# observations\n")
        sb.append("# id,site,type,x_km,y_km,value_m,sigma_white_m,group,epoch,los_e,los_n,los_u,region\n")
        for (o in bundle.observations) {
            sb.append(
                listOf(
                    o.id, o.siteId, o.type, o.x, o.y, o.value, o.sigmaWhite,
                    o.groupId, o.epoch,
                    o.losE?.toString() ?: "", o.losN?.toString() ?: "",
                    o.losU?.toString() ?: "", o.region,
                ).joinToString(","),
            ).append("\n")
        }
        return sb.toString()
    }
}
