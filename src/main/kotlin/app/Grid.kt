package app

import kotlinx.serialization.Serializable

@Serializable
data class GridPoint(
    val x: Double, val y: Double,
    val ue: Double, val un: Double, val uz: Double,
    val losA84: Double, val losD35: Double,
)

object GridRenderer {
    private val losA84 = LosVec(-0.462, -0.117, 0.879).let {
        val m = kotlin.math.sqrt(it.e * it.e + it.n * it.n + it.u * it.u)
        Triple(it.e / m, it.n / m, it.u / m)
    }
    private val losD35 = LosVec(0.403, 0.092, 0.910).let {
        val m = kotlin.math.sqrt(it.e * it.e + it.n * it.n + it.u * it.u)
        Triple(it.e / m, it.n / m, it.u / m)
    }

    fun render(type: ModelType, p: SourceParams, span: Double = 10.0, step: Double = 1.0): List<GridPoint> {
        val engine = ForwardEngine(type)
        val out = mutableListOf<GridPoint>()
        var y = -span
        while (y <= span + 1e-9) {
            var x = -span
            while (x <= span + 1e-9) {
                val u = engine.predict3(Site("grid", x, y), p)
                out.add(GridPoint(
                    x, y, u.x, u.y, u.z,
                    losA84.first * u.x + losA84.second * u.y + losA84.third * u.z,
                    losD35.first * u.x + losD35.second * u.y + losD35.third * u.z,
                ))
                x += step
            }
            y += step
        }
        return out
    }
}
