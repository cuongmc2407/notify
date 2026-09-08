package com.notifybridge.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Hang doi thong bao cho gui len server.
 *
 * Dung SQLiteOpenHelper cua he thong thay vi Room: bang chi co mot, khong can
 * annotation processor, build nhanh va it phu thuoc hon.
 */
class Outbox private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    data class Row(val id: Long, val json: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
              id         INTEGER PRIMARY KEY AUTOINCREMENT,
              uid        TEXT NOT NULL UNIQUE,
              json       TEXT NOT NULL,
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_outbox_created ON $TABLE (created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    /** Them mot thong bao vao hang doi. Tra ve false neu uid da ton tai. */
    fun enqueue(uid: String, json: String): Boolean {
        val values = ContentValues().apply {
            put("uid", uid)
            put("json", json)
            put("created_at", System.currentTimeMillis())
        }
        val id = writableDatabase.insertWithOnConflict(
            TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        if (id != -1L) trim()
        return id != -1L
    }

    /** Lay tam nhung ban ghi cu nhat de gui. */
    fun peek(limit: Int): List<Row> {
        val rows = ArrayList<Row>(limit)
        readableDatabase.query(
            TABLE, arrayOf("id", "json"), null, null, null, null, "id ASC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) rows.add(Row(c.getLong(0), c.getString(1)))
        }
        return rows
    }

    /** Xoa cac ban ghi da gui thanh cong. */
    fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString(",") { "?" }
        writableDatabase.execSQL(
            "DELETE FROM $TABLE WHERE id IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray()
        )
    }

    fun count(): Long {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM $TABLE")
    }

    /** Neu mat mang qua lau, bo bot ban ghi cu nhat de khong phinh vo han. */
    private fun trim() {
        val n = count()
        if (n <= MAX_ROWS) return
        writableDatabase.execSQL(
            "DELETE FROM $TABLE WHERE id IN (SELECT id FROM $TABLE ORDER BY id ASC LIMIT ?)",
            arrayOf((n - MAX_ROWS).toString())
        )
    }

    companion object {
        private const val DB_NAME = "outbox.db"
        private const val DB_VERSION = 1
        private const val TABLE = "outbox"
        private const val MAX_ROWS = 5000L

        @Volatile
        private var instance: Outbox? = null

        fun get(context: Context): Outbox =
            instance ?: synchronized(this) {
                instance ?: Outbox(context).also { instance = it }
            }
    }
}
