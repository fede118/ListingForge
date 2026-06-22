package com.section11.listingforge.token

import java.time.Instant
import javax.sql.DataSource

/**
 * SQLite-backed TokenStore using plain JDBC + prepared statements. Every value
 * is parameterised (the '?' placeholders), so there is no string-built SQL and
 * therefore no injection surface. The upsert keeps exactly one row per user.
 *
 * `expires_at` is stored as epoch millis (INTEGER) â€” comparing instants as
 * numbers is simpler and timezone-proof.
 */
class SqliteTokenStore(private val dataSource: DataSource) : TokenStore {

    override fun save(record: TokenRecord) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO tokens (user_id, access_token, refresh_token, expires_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                    access_token  = excluded.access_token,
                    refresh_token = excluded.refresh_token,
                    expires_at    = excluded.expires_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, record.userId)
                ps.setString(2, record.accessToken)
                ps.setString(3, record.refreshToken)
                ps.setLong(4, record.expiresAt.toEpochMilli())
                ps.executeUpdate()
            }
        }
    }

    override fun get(userId: String): TokenRecord? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT access_token, refresh_token, expires_at FROM tokens WHERE user_id = ?"
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    TokenRecord(
                        userId = userId,
                        accessToken = rs.getString("access_token"),
                        refreshToken = rs.getString("refresh_token"),
                        expiresAt = Instant.ofEpochMilli(rs.getLong("expires_at")),
                    )
                }
            }
        }

    override fun delete(userId: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM tokens WHERE user_id = ?").use { ps ->
                ps.setString(1, userId)
                ps.executeUpdate()
            }
        }
    }
}
