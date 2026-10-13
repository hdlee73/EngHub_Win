package android.database

import java.io.Closeable

interface Cursor : Closeable {
    val count: Int
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun getString(columnIndex: Int): String
    fun getLong(columnIndex: Int): Long
    fun getInt(columnIndex: Int): Int
    fun getDouble(columnIndex: Int): Double
    fun getColumnIndex(columnName: String): Int
    fun isNull(columnIndex: Int): Boolean
    override fun close()
}

class MatrixCursor(
    private val columnNames: Array<String>,
    private val rows: List<Array<Any?>> = emptyList()
) : Cursor {
    private var pos = -1
    override val count: Int get() = rows.size

    override fun moveToFirst(): Boolean {
        pos = 0
        return rows.isNotEmpty()
    }

    override fun moveToNext(): Boolean {
        pos++
        return pos in rows.indices
    }

    override fun getString(columnIndex: Int): String =
        rows.getOrNull(pos)?.getOrNull(columnIndex)?.toString() ?: ""

    override fun getLong(columnIndex: Int): Long =
        (rows.getOrNull(pos)?.getOrNull(columnIndex) as? Number)?.toLong()
            ?: getString(columnIndex).toLongOrNull() ?: 0L

    override fun getInt(columnIndex: Int): Int =
        (rows.getOrNull(pos)?.getOrNull(columnIndex) as? Number)?.toInt()
            ?: getString(columnIndex).toIntOrNull() ?: 0

    override fun getDouble(columnIndex: Int): Double =
        (rows.getOrNull(pos)?.getOrNull(columnIndex) as? Number)?.toDouble()
            ?: getString(columnIndex).toDoubleOrNull() ?: 0.0

    override fun getColumnIndex(columnName: String): Int =
        columnNames.indexOfFirst { it.equals(columnName, ignoreCase = true) }

    override fun isNull(columnIndex: Int): Boolean =
        rows.getOrNull(pos)?.getOrNull(columnIndex) == null

    override fun close() {}
}
