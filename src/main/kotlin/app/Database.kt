package app

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class Database(private val path: String) {
    val connection: Connection

    init {
        Class.forName("org.sqlite.JDBC")
        if (path != ":memory:") {
            val p: Path = Paths.get(path)
            if (p.parent != null) Files.createDirectories(p.parent)
        }
        connection = DriverManager.getConnection("jdbc:sqlite:$path")
        connection.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            schema(st)
        }
    }

    private fun schema(st: Statement) {
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS datasets (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              crs TEXT NOT NULL,
              transform_version TEXT NOT NULL,
              origin_lat REAL,
              origin_lon REAL,
              imported_at TEXT NOT NULL,
              source_hash TEXT NOT NULL,
              note TEXT NOT NULL DEFAULT ''
            )""".trimIndent(),
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS cov_groups (
              id TEXT NOT NULL,
              dataset_id INTEGER NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
              label TEXT NOT NULL,
              obs_type TEXT NOT NULL,
              epoch TEXT NOT NULL,
              sigma_cme REAL NOT NULL,
              PRIMARY KEY (dataset_id, id)
            )""".trimIndent(),
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS observations (
              id TEXT NOT NULL,
              dataset_id INTEGER NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
              site_id TEXT NOT NULL,
              obs_type TEXT NOT NULL,
              x REAL NOT NULL,
              y REAL NOT NULL,
              value REAL NOT NULL,
              sigma_white REAL NOT NULL,
              group_id TEXT NOT NULL,
              epoch TEXT NOT NULL,
              los_e REAL,
              los_n REAL,
              los_u REAL,
              region TEXT NOT NULL DEFAULT 'default',
              PRIMARY KEY (dataset_id, id)
            )""".trimIndent(),
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS runs (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              dataset_id INTEGER NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
              model_type TEXT NOT NULL,
              reg_lambda REAL NOT NULL,
              seed INTEGER NOT NULL,
              starts INTEGER NOT NULL,
              max_iter INTEGER NOT NULL,
              bounds_json TEXT NOT NULL,
              created_at TEXT NOT NULL,
              engine_version TEXT NOT NULL,
              coord_version TEXT NOT NULL
            )""".trimIndent(),
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS solutions (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              run_id INTEGER NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
              start_index INTEGER NOT NULL,
              rank INTEGER NOT NULL,
              objective REAL NOT NULL,
              chi2 REAL NOT NULL,
              n_obs INTEGER NOT NULL,
              n_params INTEGER NOT NULL,
              bic REAL NOT NULL,
              converged INTEGER NOT NULL,
              iterations INTEGER NOT NULL,
              active_bounds INTEGER NOT NULL,
              depth_at_upper INTEGER NOT NULL,
              depth_at_lower INTEGER NOT NULL,
              size_at_upper TEXT NOT NULL,
              active_detail TEXT NOT NULL,
              params_json TEXT NOT NULL
            )""".trimIndent(),
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS predictions (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              solution_id INTEGER NOT NULL REFERENCES solutions(id) ON DELETE CASCADE,
              observation_id TEXT NOT NULL,
              observed REAL NOT NULL,
              predicted REAL NOT NULL,
              residual REAL NOT NULL,
              obs_type TEXT NOT NULL,
              epoch TEXT NOT NULL,
              region TEXT NOT NULL,
              x REAL NOT NULL,
              y REAL NOT NULL,
              los_e REAL,
              los_n REAL,
              los_u REAL,
              engine_version TEXT NOT NULL,
              coord_version TEXT NOT NULL,
              transform_version TEXT NOT NULL,
              depth_sign TEXT NOT NULL,
              params_json TEXT NOT NULL
            )""".trimIndent(),
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS start_points (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              run_id INTEGER NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
              start_index INTEGER NOT NULL,
              params_json TEXT NOT NULL,
              objective REAL NOT NULL,
              iterations INTEGER NOT NULL,
              converged INTEGER NOT NULL
            )""".trimIndent(),
        )
    }

    fun close() = connection.close()
}
