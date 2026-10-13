package android.os

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

object Build {
    object VERSION {
        const val SDK_INT: Int = 34
    }
    object VERSION_CODES {
        const val O = 26
        const val P = 28
        const val Q = 29
        const val R = 30
        const val S = 31
        const val TIRAMISU = 33
        const val UPSIDE_DOWN_CAKE = 34
    }
}

class Bundle {
    private val map = ConcurrentHashMap<String, Any?>()

    fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    fun getString(key: String, defaultValue: String? = null): String? =
        (map[key] as? String) ?: defaultValue

    fun putFloat(key: String, value: Float) {
        map[key] = value
    }

    fun getFloat(key: String, defaultValue: Float = 0f): Float =
        (map[key] as? Number)?.toFloat() ?: defaultValue

    fun putInt(key: String, value: Int) {
        map[key] = value
    }

    fun getInt(key: String, defaultValue: Int = 0): Int =
        (map[key] as? Number)?.toInt() ?: defaultValue

    fun putLong(key: String, value: Long) {
        map[key] = value
    }

    fun getLong(key: String, defaultValue: Long = 0L): Long =
        (map[key] as? Number)?.toLong() ?: defaultValue

    fun putBoolean(key: String, value: Boolean) {
        map[key] = value
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean =
        (map[key] as? Boolean) ?: defaultValue

    fun putStringArrayList(key: String, value: ArrayList<String>?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    fun getStringArrayList(key: String): ArrayList<String>? =
        map[key] as? ArrayList<String>
}

object Environment {
    const val DIRECTORY_DOWNLOADS = "Downloads"
    const val DIRECTORY_MUSIC = "Music"
    const val DIRECTORY_DOCUMENTS = "Documents"

    fun getExternalStoragePublicDirectory(type: String): File =
        File(System.getProperty("user.home"), type).apply { mkdirs() }
}

class Looper private constructor() {
    companion object {
        private val MAIN = Looper()
        fun getMainLooper(): Looper = MAIN
        fun myLooper(): Looper = MAIN
    }
}

class Handler(val looper: Looper = Looper.getMainLooper()) {
    companion object {
        private val scheduler = Executors.newScheduledThreadPool(2) { r ->
            Thread(r, "EngHub-Handler").apply { isDaemon = true }
        }
    }

    private val tasks = ConcurrentHashMap<Runnable, MutableList<ScheduledFuture<*>>>()

    fun post(r: Runnable): Boolean {
        if (SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(r)
        } else {
            SwingUtilities.invokeLater(r)
        }
        return true
    }

    fun postDelayed(r: Runnable, delayMillis: Long): Boolean {
        val future = scheduler.schedule({
            tasks[r]?.removeIf { it.isDone }
            SwingUtilities.invokeLater(r)
        }, delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        tasks.computeIfAbsent(r) { mutableListOf() }.add(future)
        return true
    }

    fun removeCallbacks(r: Runnable) {
        tasks.remove(r)?.forEach { it.cancel(false) }
    }

    fun removeCallbacksAndMessages(token: Any?) {
        tasks.values.forEach { list -> list.forEach { it.cancel(false) } }
        tasks.clear()
    }
}

interface IBinder

open class Binder : IBinder

object SystemClock {
    private val startNano = System.nanoTime()
    fun elapsedRealtime(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNano) + 1_000L
    fun uptimeMillis(): Long = elapsedRealtime()
}

class PowerManager {
    companion object {
        const val PARTIAL_WAKE_LOCK = 1
    }

    class WakeLock {
        var isHeld: Boolean = false
            private set

        fun acquire(timeout: Long = 0L) {
            isHeld = true
        }

        fun release() {
            isHeld = false
        }
    }

    fun newWakeLock(levelAndFlags: Int, tag: String): WakeLock = WakeLock()
}

class ParcelFileDescriptor(
    val file: File? = null,
    internal val inputStream: InputStream? = null,
    internal val outputStream: OutputStream? = null
) : Closeable {
    companion object {
        const val MODE_READ_ONLY = 0x10000000

        fun open(file: File, mode: Int): ParcelFileDescriptor = ParcelFileDescriptor(file = file)

        fun createPipe(): Array<ParcelFileDescriptor> {
            val pos = PipedOutputStream()
            val pis = PipedInputStream(pos, 16384)
            return arrayOf(
                ParcelFileDescriptor(inputStream = pis),
                ParcelFileDescriptor(outputStream = pos)
            )
        }
    }

    class AutoCloseOutputStream(private val pfd: ParcelFileDescriptor) : OutputStream() {
        private val out: OutputStream = pfd.outputStream ?: pfd.file?.let { FileOutputStream(it) } ?: OutputStream.nullOutputStream()
        override fun write(b: Int) = out.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun flush() = out.flush()
        override fun close() {
            runCatching { out.close() }
            pfd.close()
        }
    }

    class AutoCloseInputStream(private val pfd: ParcelFileDescriptor) : InputStream() {
        private val ins: InputStream = pfd.inputStream ?: pfd.file?.let { FileInputStream(it) } ?: InputStream.nullInputStream()
        override fun read(): Int = ins.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = ins.read(b, off, len)
        override fun close() {
            runCatching { ins.close() }
            pfd.close()
        }
    }

    override fun close() {
        runCatching { inputStream?.close() }
        runCatching { outputStream?.close() }
    }
}
