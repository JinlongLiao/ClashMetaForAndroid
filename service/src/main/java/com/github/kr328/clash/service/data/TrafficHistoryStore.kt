package com.github.kr328.clash.service.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** Persists compact 15-minute traffic buckets separately from profile schema migrations. */
class TrafficHistoryStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "traffic-history.db", null, 1) {
    /** Creates the derived traffic aggregation table. */
    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL("CREATE TABLE traffic_history (bucket INTEGER PRIMARY KEY, upload INTEGER NOT NULL, download INTEGER NOT NULL)")
    }

    /** Recreates this derived cache if its private schema changes. */
    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        database.execSQL("DROP TABLE IF EXISTS traffic_history")
        onCreate(database)
    }

    /** Adds positive deltas to the current bucket and prunes data older than 31 days. */
    fun recordTrafficDelta(timestamp: Long, upload: Long, download: Long) {
        if (upload <= 0L && download <= 0L) {
            return
        }
        val bucket = timestamp / BUCKET_MILLIS * BUCKET_MILLIS
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL(
                "INSERT OR IGNORE INTO traffic_history(bucket, upload, download) VALUES(?, 0, 0)",
                arrayOf(bucket),
            )
            writableDatabase.execSQL(
                "UPDATE traffic_history SET upload=upload+?, download=download+? WHERE bucket=?",
                arrayOf(upload.coerceAtLeast(0L), download.coerceAtLeast(0L), bucket),
            )
            writableDatabase.delete(
                "traffic_history",
                "bucket < ?",
                arrayOf((timestamp - RETENTION_MILLIS).toString()),
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /**
     * Creates the current sampling bucket even when the core counters are still zero.
     *
     * This makes service sampling observable without inflating later traffic totals. The method
     * is idempotent and intentionally performs no counter update for an existing bucket.
     */
    fun ensureTrafficBucket(timestamp: Long) {
        val bucket = timestamp / BUCKET_MILLIS * BUCKET_MILLIS
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO traffic_history(bucket, upload, download) VALUES(?, 0, 0)",
            arrayOf(bucket),
        )
        pruneExpiredTrafficHistory(timestamp)
    }

    /** Deletes all persisted traffic buckets and returns the number removed. */
    fun clearTrafficHistory(): Int {
        return writableDatabase.delete("traffic_history", null, null)
    }

    /** Removes buckets outside the fixed 31-day retention window. */
    private fun pruneExpiredTrafficHistory(timestamp: Long) {
        writableDatabase.delete(
            "traffic_history",
            "bucket < ?",
            arrayOf((timestamp - RETENTION_MILLIS).toString()),
        )
    }

    /** Returns recent buckets ordered chronologically as a compact JSON array. */
    fun queryTrafficHistory(since: Long): String {
        val result = JSONArray()
        readableDatabase.query(
            "traffic_history", arrayOf("bucket", "upload", "download"), "bucket >= ?",
            arrayOf(since.toString()), null, null, "bucket ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.put(JSONObject().put("bucket", cursor.getLong(0)).put("upload", cursor.getLong(1)).put("download", cursor.getLong(2)))
            }
        }
        return result.toString()
    }

    companion object {
        private const val BUCKET_MILLIS = 15L * 60L * 1000L
        private const val RETENTION_MILLIS = 31L * 24L * 60L * 60L * 1000L
    }
}
