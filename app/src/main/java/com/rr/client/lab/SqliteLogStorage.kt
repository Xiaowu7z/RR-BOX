package com.rr.client.lab

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** A separate, private database. Profile/credential storage and its migrations are untouched. */
internal class SqliteLogStorage(directory: File) : LogStorage {
    private val database: SQLiteDatabase
    private var retentionLimit = 2000
    private var totalCount = 0L
    private var maxId = 0L

    init {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建日志目录" }
        database = SQLiteDatabase.openOrCreateDatabase(File(directory, "history.sqlite"), null)
        try {
            // Must precede the first table. Reuse pages during normal retention; reclaim on clear/reduce.
            database.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
            database.enableWriteAheadLogging()
            database.execSQL("CREATE TABLE IF NOT EXISTS settings (name TEXT PRIMARY KEY, value INTEGER NOT NULL)")
            database.execSQL("CREATE TABLE IF NOT EXISTS logs (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, channel TEXT NOT NULL, message TEXT NOT NULL)")
            database.execSQL("CREATE INDEX IF NOT EXISTS logs_chronological ON logs(timestamp, id)")
            database.execSQL("CREATE INDEX IF NOT EXISTS logs_channel ON logs(channel, id)")
            database.execSQL("INSERT OR IGNORE INTO settings(name,value) VALUES ('retention',2000)")
            retentionLimit = scalar("SELECT value FROM settings WHERE name='retention'").toInt()
            validateLogRetention(retentionLimit)
            totalCount = scalar("SELECT COUNT(*) FROM logs")
            maxId = scalar("SELECT COALESCE(MAX(id),0) FROM logs")
            prune()
        } catch (error: Exception) {
            database.close()
            throw error
        }
    }

    override fun metadata() = LogStorageMetadata(retentionLimit, totalCount, maxId)

    override fun append(entries: List<LabLogEntry>): List<RRStoredLogEntry> {
        if (entries.isEmpty()) return emptyList()
        val inserted = ArrayList<RRStoredLogEntry>(entries.size)
        val oldCount = totalCount
        val oldMax = maxId
        database.beginTransaction()
        try {
            database.compileStatement("INSERT INTO logs(timestamp,channel,message) VALUES (?,?,?)").use { statement ->
                entries.forEach { entry ->
                    statement.bindLong(1, entry.timestamp)
                    statement.bindString(2, entry.channel)
                    statement.bindString(3, entry.message)
                    val id = statement.executeInsert()
                    check(id > 0) { "日志写入未完成" }
                    inserted.add(RRStoredLogEntry(id, entry.timestamp, entry.channel, entry.message))
                    maxId = maxOf(maxId, id)
                    totalCount++
                }
            }
            prune()
            database.setTransactionSuccessful()
        } catch (error: Exception) {
            totalCount = oldCount
            maxId = oldMax
            throw error
        } finally {
            database.endTransaction()
        }
        return inserted
    }

    override fun deleteIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        val oldCount = totalCount
        database.beginTransaction()
        try {
            database.compileStatement("DELETE FROM logs WHERE id=?").use { statement ->
                ids.forEach { id -> statement.bindLong(1, id); totalCount -= statement.executeUpdateDelete() }
            }
            database.setTransactionSuccessful()
        } catch (error: Exception) {
            totalCount = oldCount
            throw error
        } finally {
            database.endTransaction()
        }
    }

    override fun setRetentionLimit(limit: Int) {
        validateLogRetention(limit)
        val previous = retentionLimit
        val oldCount = totalCount
        database.beginTransaction()
        try {
            database.execSQL("UPDATE settings SET value=? WHERE name='retention'", arrayOf(limit))
            retentionLimit = limit
            prune()
            database.setTransactionSuccessful()
        } catch (error: Exception) {
            retentionLimit = previous
            totalCount = oldCount
            throw error
        } finally {
            database.endTransaction()
        }
        if (limit != 0 && (previous == 0 || limit < previous)) reclaimSpace()
    }

    override fun clear() {
        database.execSQL("DELETE FROM logs")
        totalCount = 0
        // AUTOINCREMENT is intentionally not reset: an old UI cursor never aliases a new row.
        reclaimSpace()
    }

    private fun prune() {
        if (retentionLimit == 0 || totalCount <= retentionLimit) return
        database.compileStatement("DELETE FROM logs WHERE id < (SELECT id FROM logs ORDER BY id DESC LIMIT 1 OFFSET ?)").use {
            it.bindLong(1, retentionLimit.toLong() - 1)
            totalCount -= it.executeUpdateDelete()
        }
    }

    override fun query(filter: RRLogFilter, beforeId: Long?, limit: Int): RRLogPage {
        val (where, args) = condition(filter)
        val count = if (where.isEmpty()) totalCount else scalar("SELECT COUNT(*) FROM logs WHERE $where", args)
        val pageWhere = listOfNotNull(where.takeIf(String::isNotEmpty), beforeId?.let { "id < ?" }).joinToString(" AND ")
        val pageArgs = args + (beforeId?.let { arrayOf(it.toString()) } ?: emptyArray())
        val rows = database.rawQuery(
            "SELECT id,timestamp,channel,message FROM logs" +
                (if (pageWhere.isEmpty()) "" else " WHERE $pageWhere") + " ORDER BY id DESC LIMIT ${limit + 1}", pageArgs
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.entry()) } }
        return RRLogPage(rows.take(limit), count, if (rows.size > limit) rows[limit - 1].id else null)
    }

    override fun visitChronological(filter: RRLogFilter, visitor: (RRStoredLogEntry) -> Unit): Long {
        val (where, args) = condition(filter)
        var count = 0L
        // The owner serializes mutations until this cursor has streamed into a private staging file.
        database.rawQuery("SELECT id,timestamp,channel,message FROM logs" +
            (if (where.isEmpty()) "" else " WHERE $where") + " ORDER BY timestamp ASC,id ASC", args).use { cursor ->
            while (cursor.moveToNext()) { visitor(cursor.entry()); count++ }
        }
        return count
    }

    private fun condition(filter: RRLogFilter): Pair<String, Array<String>> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (!filter.channel.isNullOrBlank()) { clauses.add("channel = ?"); args.add(filter.channel) }
        if (filter.search.isNotBlank()) {
            // User text is literal, never SQL or LIKE wildcard syntax.
            val search = "%" + filter.search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            clauses.add("(message LIKE ? ESCAPE '\\' OR channel LIKE ? ESCAPE '\\')")
            args.add(search); args.add(search)
        }
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }

    private fun Cursor.entry() = RRStoredLogEntry(getLong(0), getLong(1), getString(2), getString(3))
    private fun scalar(sql: String, args: Array<String> = emptyArray()): Long =
        database.rawQuery(sql, args).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun reclaimSpace() {
        database.rawQuery("PRAGMA incremental_vacuum", null).use { while (it.moveToNext()) Unit }
        database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { while (it.moveToNext()) Unit }
    }

    override fun close() = database.close()
}
