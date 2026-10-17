package android.database.sqlite

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import java.io.Closeable
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.sqlite.SQLiteConfig

class SQLiteDatabase private constructor(val connection: Connection, val path: String) : Closeable {
    interface CursorFactory

    companion object {
        const val OPEN_READONLY = 1
        const val OPEN_READWRITE = 0
        const val CONFLICT_REPLACE = 5

        init {
            runCatching { Class.forName("org.sqlite.JDBC") }
        }

        fun openDatabase(path: String, factory: CursorFactory?, flags: Int): SQLiteDatabase {
            // ":memory:" must be a real in-memory database, not a file literally named ":memory:".
            if (path == ":memory:") {
                return SQLiteDatabase(DriverManager.getConnection("jdbc:sqlite::memory:"), ":memory:")
            }
            val file = File(path)
            if (flags == OPEN_READONLY && !file.exists()) {
                throw IllegalStateException("Database does not exist: $path")
            }
            file.parentFile?.mkdirs()
            val config = SQLiteConfig()
            if (flags == OPEN_READONLY) {
                config.setReadOnly(true)
            }
            val conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}", config.toProperties())
            return SQLiteDatabase(conn, file.absolutePath)
        }
    }

    @Synchronized
    fun rawQuery(sql: String, selectionArgs: Array<String>?): Cursor {
        val stmt = connection.prepareStatement(sql)
        selectionArgs?.forEachIndexed { idx, arg ->
            stmt.setString(idx + 1, arg)
        }
        stmt.use { ps ->
            ps.executeQuery().use { rs ->
                val meta = rs.metaData
                val colCount = meta.columnCount
                val cols = Array(colCount) { i -> meta.getColumnLabel(i + 1) ?: meta.getColumnName(i + 1) ?: "col_$i" }
                val rows = mutableListOf<Array<Any?>>()
                while (rs.next()) {
                    val row = Array<Any?>(colCount) { i -> rs.getObject(i + 1) }
                    rows.add(row)
                }
                return MatrixCursor(cols, rows)
            }
        }
    }

    @Synchronized
    fun execSQL(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    @Synchronized
    fun execSQL(sql: String, bindArgs: Array<Any?>) {
        connection.prepareStatement(sql).use { ps ->
            bindArgs.forEachIndexed { idx, v -> ps.setObject(idx + 1, v) }
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun insertWithOnConflict(
        table: String,
        nullColumnHack: String?,
        initialValues: ContentValues,
        conflictAlgorithm: Int
    ): Long {
        val entries = initialValues.valueSet().toList()
        if (entries.isEmpty()) return -1L
        val orClause = if (conflictAlgorithm == CONFLICT_REPLACE) "OR REPLACE " else ""
        val cols = entries.joinToString(",") { it.key }
        val placeholders = entries.joinToString(",") { "?" }
        val sql = "INSERT ${orClause}INTO $table ($cols) VALUES ($placeholders)"
        connection.prepareStatement(sql).use { ps ->
            entries.forEachIndexed { idx, entry -> ps.setObject(idx + 1, entry.value) }
            val updated = ps.executeUpdate()
            if (updated <= 0) return -1L
        }
        connection.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs ->
                return if (rs.next()) rs.getLong(1) else 1L
            }
        }
    }

    @Synchronized
    fun update(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?
    ): Int {
        val entries = values.valueSet().toList()
        if (entries.isEmpty()) return 0
        val setClause = entries.joinToString(",") { "${it.key}=?" }
        val sql = buildString {
            append("UPDATE ").append(table).append(" SET ").append(setClause)
            if (!whereClause.isNullOrBlank()) append(" WHERE ").append(whereClause)
        }
        connection.prepareStatement(sql).use { ps ->
            var idx = 1
            entries.forEach { entry -> ps.setObject(idx++, entry.value) }
            whereArgs?.forEach { arg -> ps.setString(idx++, arg) }
            return ps.executeUpdate()
        }
    }

    @Synchronized
    fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int {
        val sql = buildString {
            append("DELETE FROM ").append(table)
            if (!whereClause.isNullOrBlank()) append(" WHERE ").append(whereClause)
        }
        connection.prepareStatement(sql).use { ps ->
            whereArgs?.forEachIndexed { idx, arg -> ps.setString(idx + 1, arg) }
            return ps.executeUpdate()
        }
    }

    var version: Int
        @Synchronized get() {
            connection.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
        @Synchronized set(value) {
            connection.createStatement().use { st ->
                st.execute("PRAGMA user_version = $value")
            }
        }

    override fun close() {
        runCatching { connection.close() }
    }
}

abstract class SQLiteOpenHelper(
    private val context: Context,
    private val name: String?,
    private val factory: SQLiteDatabase.CursorFactory?,
    private val newVersion: Int
) : Closeable {
    private var db: SQLiteDatabase? = null

    abstract fun onCreate(db: SQLiteDatabase)
    abstract fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int)

    val readableDatabase: SQLiteDatabase
        @Synchronized get() = getOrOpen()

    val writableDatabase: SQLiteDatabase
        @Synchronized get() = getOrOpen()

    @Synchronized
    private fun getOrOpen(): SQLiteDatabase {
        db?.let { if (!it.connection.isClosed) return it }
        val path = if (name == null) ":memory:" else context.getDatabasePath(name).absolutePath
        val opened = SQLiteDatabase.openDatabase(path, factory, SQLiteDatabase.OPEN_READWRITE)
        val curVer = opened.version
        if (curVer == 0) {
            onCreate(opened)
            opened.version = newVersion
        } else if (curVer < newVersion) {
            onUpgrade(opened, curVer, newVersion)
            opened.version = newVersion
        }
        db = opened
        return opened
    }

    @Synchronized
    override fun close() {
        db?.close()
        db = null
    }
}
