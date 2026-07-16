package com.section11.listingforge.template

import kotlinx.serialization.json.Json
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

/**
 * SQLite-backed TemplateStore, following the same plain-JDBC + prepared
 * statement shape as SqliteTokenStore. `tags` has no fixed arity (Etsy caps it
 * at 13, but this layer doesn't enforce that), so it's stored as a JSON array
 * in a single TEXT column rather than a join table - simplest thing that
 * works for a handful of tags per template.
 *
 * Every statement filters on `shop_id` in addition to `id`, so a template
 * belonging to another shop is invisible here already - callers never see the
 * difference between "wrong shop" and "doesn't exist".
 */
class SqliteTemplateStore(private val dataSource: DataSource) : TemplateStore {
    private val json = Json

    /**
     * created_at/updated_at are stored as epoch millis, so the Instant handed
     * back to the caller here is truncated up front - otherwise it would carry
     * sub-millisecond precision the DB round trip can't reproduce, and a
     * freshly-created record would never `equals()` the same row read back
     * from list()/update().
     */
    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    override fun create(shopId: Long, fields: TemplateFields): TemplateRecord {
        val now = now()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO templates (
                    shop_id, name, title, description, price, quantity, tags,
                    who_made, when_made, taxonomy_id, taxonomy_path, specs_text,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.bindFields(shopId, fields)
                ps.setLong(13, now.toEpochMilli())
                ps.setLong(14, now.toEpochMilli())
                ps.executeUpdate()
                ps.generatedKeys.use { keys ->
                    keys.next()
                    return TemplateRecord(keys.getLong(1), shopId, fields, now, now)
                }
            }
        }
    }

    override fun list(shopId: Long): List<TemplateRecord> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT * FROM templates WHERE shop_id = ? ORDER BY updated_at DESC"
            ).use { ps ->
                ps.setLong(1, shopId)
                ps.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) rs.toRecord() else null }.toList()
                }
            }
        }

    override fun update(shopId: Long, id: Long, fields: TemplateFields): TemplateRecord? {
        val now = now()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE templates SET
                    name = ?, title = ?, description = ?, price = ?, quantity = ?,
                    tags = ?, who_made = ?, when_made = ?, taxonomy_id = ?,
                    taxonomy_path = ?, specs_text = ?, updated_at = ?
                WHERE id = ? AND shop_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, fields.name)
                ps.setString(2, fields.title)
                ps.setString(3, fields.description)
                ps.setString(4, fields.price)
                ps.setString(5, fields.quantity)
                ps.setString(6, json.encodeToString(fields.tags))
                ps.setString(7, fields.whoMade)
                ps.setString(8, fields.whenMade)
                ps.setObjectOrNull(9, fields.taxonomyId)
                ps.setStringOrNull(10, fields.taxonomyPath)
                ps.setString(11, fields.specsText)
                ps.setLong(12, now.toEpochMilli())
                ps.setLong(13, id)
                ps.setLong(14, shopId)
                if (ps.executeUpdate() == 0) return null
            }
        }
        return get(shopId, id, now, fields)
    }

    override fun delete(shopId: Long, id: Long): Boolean =
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM templates WHERE id = ? AND shop_id = ?").use { ps ->
                ps.setLong(1, id)
                ps.setLong(2, shopId)
                ps.executeUpdate() > 0
            }
        }

    /** Re-reads created_at rather than threading it through the caller, since update() doesn't have it. */
    private fun get(shopId: Long, id: Long, updatedAt: Instant, fields: TemplateFields): TemplateRecord =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT created_at FROM templates WHERE id = ? AND shop_id = ?").use { ps ->
                ps.setLong(1, id)
                ps.setLong(2, shopId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    val createdAt = Instant.ofEpochMilli(rs.getLong("created_at"))
                    TemplateRecord(id, shopId, fields, createdAt, updatedAt)
                }
            }
        }

    private fun PreparedStatement.bindFields(shopId: Long, fields: TemplateFields) {
        setLong(1, shopId)
        setString(2, fields.name)
        setString(3, fields.title)
        setString(4, fields.description)
        setString(5, fields.price)
        setString(6, fields.quantity)
        setString(7, json.encodeToString(fields.tags))
        setString(8, fields.whoMade)
        setString(9, fields.whenMade)
        setObjectOrNull(10, fields.taxonomyId)
        setStringOrNull(11, fields.taxonomyPath)
        setString(12, fields.specsText)
    }

    private fun PreparedStatement.setObjectOrNull(index: Int, value: Long?) {
        if (value == null) setNull(index, Types.INTEGER) else setLong(index, value)
    }

    private fun PreparedStatement.setStringOrNull(index: Int, value: String?) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }

    private fun ResultSet.toRecord(): TemplateRecord {
        val taxonomyId = getLong("taxonomy_id").takeUnless { wasNull() }
        val fields = TemplateFields(
            name = getString("name"),
            title = getString("title"),
            description = getString("description"),
            price = getString("price"),
            quantity = getString("quantity"),
            tags = json.decodeFromString(getString("tags")),
            whoMade = getString("who_made"),
            whenMade = getString("when_made"),
            taxonomyId = taxonomyId,
            taxonomyPath = getString("taxonomy_path"),
            specsText = getString("specs_text"),
        )
        return TemplateRecord(
            id = getLong("id"),
            shopId = getLong("shop_id"),
            fields = fields,
            createdAt = Instant.ofEpochMilli(getLong("created_at")),
            updatedAt = Instant.ofEpochMilli(getLong("updated_at")),
        )
    }
}
