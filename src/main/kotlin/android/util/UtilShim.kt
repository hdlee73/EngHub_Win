package android.util

import java.util.LinkedHashMap

open class LruCache<K, V>(private val maxSize: Int) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var currentSize = 0

    protected open fun sizeOf(key: K, value: V): Int = 1

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V): V? {
        val prev = map.put(key, value)
        if (prev != null) currentSize -= sizeOf(key, prev)
        currentSize += sizeOf(key, value)
        trimToSize(maxSize)
        return prev
    }

    @Synchronized
    fun evictAll() {
        map.clear()
        currentSize = 0
    }

    @Synchronized
    private fun trimToSize(max: Int) {
        val it = map.entries.iterator()
        while (currentSize > max && it.hasNext()) {
            val entry = it.next()
            currentSize -= sizeOf(entry.key, entry.value)
            it.remove()
        }
    }
}

interface AttributeSet

object TypedValue {
    const val COMPLEX_UNIT_DIP = 1
    const val COMPLEX_UNIT_SP = 2
}

class DisplayMetrics {
    var density: Float = 1.0f
    var widthPixels: Int = 1280
    var heightPixels: Int = 860
}
