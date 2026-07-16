package com.section11.listingforge.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

/**
 * Builds the connection pool and creates the schema on startup.
 *
 * SQLite is a single-writer engine, so the pool stays small. WAL mode lets
 * readers proceed alongside the one writer, which is ample here. Note the shape:
 * everything downstream depends on the standard `DataSource` interface and has
 * no idea it's SQLite underneath â€” that's what keeps "move to Postgres later"
 * a localized change.
 */
object Database {
    fun dataSource(dbPath: String): DataSource {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:$dbPath"
            maximumPoolSize = 4
            connectionInitSql = "PRAGMA journal_mode=WAL;"
        }
        val ds = HikariDataSource(config)
        initSchema(ds)
        return ds
    }

    private fun initSchema(ds: DataSource) {
        ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS tokens (
                        user_id        TEXT PRIMARY KEY,
                        access_token   TEXT NOT NULL,
                        refresh_token  TEXT NOT NULL,
                        expires_at     INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS templates (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        shop_id       INTEGER NOT NULL,
                        name          TEXT NOT NULL,
                        title         TEXT NOT NULL,
                        description   TEXT NOT NULL,
                        price         TEXT NOT NULL,
                        quantity      TEXT NOT NULL,
                        tags          TEXT NOT NULL,
                        who_made      TEXT NOT NULL,
                        when_made     TEXT NOT NULL,
                        taxonomy_id   INTEGER,
                        taxonomy_path TEXT,
                        specs_text    TEXT NOT NULL,
                        created_at    INTEGER NOT NULL,
                        updated_at    INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_templates_shop_id ON templates(shop_id)"
                )
            }
        }
    }
}
