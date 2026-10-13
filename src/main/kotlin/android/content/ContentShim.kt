package android.content

import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.database.Cursor
import android.database.MatrixCursor
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

class ActivityNotFoundException(message: String = "") : RuntimeException(message)

class ComponentName(val packageName: String = "", val className: String = "") {
    constructor(pkg: Context, cls: Class<*>) : this(pkg.packageName, cls.name)
}

interface ServiceConnection {
    fun onServiceConnected(name: ComponentName?, service: IBinder?)
    fun onServiceDisconnected(name: ComponentName?)
}

class ContentValues {
    private val values = LinkedHashMap<String, Any?>()

    fun put(key: String, value: String?) { values[key] = value }
    fun put(key: String, value: Int?) { values[key] = value }
    fun put(key: String, value: Long?) { values[key] = value }
    fun put(key: String, value: Float?) { values[key] = value }
    fun put(key: String, value: Double?) { values[key] = value }
    fun put(key: String, value: Boolean?) { values[key] = value }
    fun getAsString(key: String): String? = values[key]?.toString()
    fun clear() = values.clear()
    fun valueSet(): Set<Map.Entry<String, Any?>> = values.entries
}

class ClipData private constructor(private val items: List<Item>) {
    class Item(val text: CharSequence? = null, val uri: Uri? = null) {
        fun coerceToText(context: Context): CharSequence = text ?: uri?.toString() ?: ""
    }

    val itemCount: Int get() = items.size
    fun getItemAt(index: Int): Item = items[index]

    companion object {
        fun newPlainText(label: CharSequence?, text: CharSequence?): ClipData =
            ClipData(listOf(Item(text = text)))

        fun newUris(uris: List<Uri>): ClipData =
            ClipData(uris.map { Item(uri = it) })
    }
}

class ClipboardManager {
    fun setPrimaryClip(clip: ClipData) {
        val text = if (clip.itemCount > 0) clip.getItemAt(0).text?.toString() ?: "" else ""
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    val primaryClip: ClipData?
        get() = runCatching {
            val cb = Toolkit.getDefaultToolkit().systemClipboard
            if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                val str = cb.getData(DataFlavor.stringFlavor) as? String
                if (str != null) ClipData.newPlainText("text", str) else null
            } else null
        }.getOrNull()
}

class Intent(
    var action: String? = null,
    var data: Uri? = null
) {
    constructor(packageContext: Context, cls: Class<*>) : this() {
        targetClass = cls
    }

    var targetClass: Class<*>? = null
    var type: String? = null
    var clipData: ClipData? = null
    var flags: Int = 0
    private val extras = Bundle()
    private val arrayExtras = ConcurrentHashMap<String, Any>()

    fun setAction(action: String?): Intent {
        this.action = action
        return this
    }

    fun setData(data: Uri?): Intent {
        this.data = data
        return this
    }

    fun setType(type: String?): Intent {
        this.type = type
        return this
    }

    fun setDataAndType(data: Uri?, type: String?): Intent {
        this.data = data
        this.type = type
        return this
    }

    fun setPackage(pkg: String?): Intent = this
    fun addCategory(category: String): Intent = this
    fun addFlags(flags: Int): Intent {
        this.flags = this.flags or flags
        return this
    }

    fun putExtra(name: String, value: String?): Intent {
        extras.putString(name, value)
        return this
    }

    fun putExtra(name: String, value: Boolean): Intent {
        extras.putBoolean(name, value)
        return this
    }

    fun putExtra(name: String, value: Int): Intent {
        extras.putInt(name, value)
        return this
    }

    fun putExtra(name: String, value: Long): Intent {
        extras.putLong(name, value)
        return this
    }

    fun putExtra(name: String, value: Array<String>): Intent {
        arrayExtras[name] = value
        return this
    }

    fun putExtra(name: String, value: Any?): Intent {
        if (value != null) arrayExtras[name] = value
        return this
    }

    fun putStringArrayListExtra(name: String, value: ArrayList<String>?): Intent {
        extras.putStringArrayList(name, value)
        return this
    }

    fun getStringExtra(name: String): String? = extras.getString(name)
    fun getBooleanExtra(name: String, defaultValue: Boolean): Boolean = extras.getBoolean(name, defaultValue)
    fun getIntExtra(name: String, defaultValue: Int): Int = extras.getInt(name, defaultValue)
    fun getLongExtra(name: String, defaultValue: Long): Long = extras.getLong(name, defaultValue)
    @Suppress("UNCHECKED_CAST")
    fun getStringArrayExtra(name: String): Array<String>? = arrayExtras[name] as? Array<String>
    fun getCharSequenceExtra(name: String): CharSequence? = extras.getString(name)
    fun removeExtra(name: String) {
        extras.putString(name, null)
        arrayExtras.remove(name)
    }

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"
        const val ACTION_GET_CONTENT = "android.intent.action.GET_CONTENT"
        const val ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT"
        const val CATEGORY_OPENABLE = "android.intent.category.OPENABLE"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val EXTRA_STREAM = "android.intent.extra.STREAM"
        const val EXTRA_PROCESS_TEXT = "android.intent.extra.PROCESS_TEXT"
        const val EXTRA_MIME_TYPES = "android.intent.extra.MIME_TYPES"
        const val EXTRA_ALLOW_MULTIPLE = "android.intent.extra.ALLOW_MULTIPLE"
        const val FLAG_GRANT_READ_URI_PERMISSION = 1
        const val FLAG_GRANT_WRITE_URI_PERMISSION = 2
        const val FLAG_GRANT_PERSISTABLE_URI_PERMISSION = 64
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        const val FLAG_ACTIVITY_SINGLE_TOP = 0x20000000
        const val FLAG_ACTIVITY_CLEAR_TOP = 0x04000000
        const val FLAG_ACTIVITY_REORDER_TO_FRONT = 0x00020000

        fun createChooser(target: Intent, title: CharSequence?): Intent = target
    }
}

class ContentResolver {
    fun openInputStream(uri: Uri): InputStream? {
        val path = uri.path ?: return null
        val file = File(path)
        return if (file.exists() && file.isFile) FileInputStream(file) else null
    }

    fun openOutputStream(uri: Uri): OutputStream? {
        val path = uri.path ?: return null
        val file = File(path)
        file.parentFile?.mkdirs()
        return FileOutputStream(file)
    }

    fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        val raw = uri.toString()
        if (raw.startsWith("content://children/")) {
            val dirPath = raw.removePrefix("content://children/")
            val dir = File(dirPath)
            if (!dir.exists() || !dir.isDirectory) return MatrixCursor(arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ))
            val rows = dir.listFiles().orEmpty().map { child ->
                val mime = if (child.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                arrayOf<Any?>(child.absolutePath, child.name, mime)
            }
            return MatrixCursor(
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                rows
            )
        }
        val path = uri.path ?: return null
        val file = File(path)
        val displayName = file.name.ifBlank { uri.lastPathSegment ?: "file" }
        val size = if (file.exists() && file.isFile) file.length() else -1L
        val cols = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID
        )
        return MatrixCursor(cols, listOf(arrayOf(displayName, size, file.absolutePath)))
    }

    fun insert(url: Uri, values: ContentValues): Uri? {
        val displayName = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) ?: "output"
        val relPath = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH) ?: "Downloads"
        val baseDir = File(System.getProperty("user.home"), relPath).apply { mkdirs() }
        var target = File(baseDir, displayName)
        var counter = 1
        val base = displayName.substringBeforeLast('.', displayName)
        val ext = displayName.substringAfterLast('.', "")
        while (target.exists()) {
            val nextName = if (ext.isNotEmpty() && base != displayName) "$base ($counter).$ext" else "$base ($counter)"
            target = File(baseDir, nextName)
            counter++
        }
        target.parentFile?.mkdirs()
        target.createNewFile()
        return Uri.fromFile(target)
    }

    fun update(uri: Uri, values: ContentValues?, where: String?, selectionArgs: Array<String>?): Int = 1

    fun delete(url: Uri, where: String?, selectionArgs: Array<String>?): Int {
        val f = File(url.path ?: return 0)
        return if (f.delete()) 1 else 0
    }

    fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {}
    fun releasePersistableUriPermission(uri: Uri, modeFlags: Int) {}
}

interface SharedPreferences {
    fun interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?)
    }

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }

    val all: Map<String, *>
    fun getString(key: String, defValue: String?): String?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun contains(key: String): Boolean
    fun edit(): Editor
    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)
    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)
}

internal class FileSharedPreferences(private val file: File) : SharedPreferences {
    private val data = ConcurrentHashMap<String, Any>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    init {
        loadFromDisk()
    }

    @Synchronized
    private fun loadFromDisk() {
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            for (key in json.keys()) {
                when (val v = json.get(key)) {
                    is JSONArray -> {
                        val set = LinkedHashSet<String>()
                        for (i in 0 until v.length()) set.add(v.getString(i))
                        data[key] = set
                    }
                    JSONObject.NULL -> Unit
                    else -> data[key] = v
                }
            }
        }
    }

    @Synchronized
    private fun saveToDisk() {
        runCatching {
            file.parentFile?.mkdirs()
            val json = JSONObject()
            for ((k, v) in data) {
                if (v is Set<*>) {
                    val arr = JSONArray()
                    v.forEach { arr.put(it?.toString() ?: "") }
                    json.put(k, arr)
                } else {
                    json.put(k, v)
                }
            }
            file.writeText(json.toString(2), Charsets.UTF_8)
        }
    }

    override val all: Map<String, *>
        get() = HashMap(data)

    override fun getString(key: String, defValue: String?): String? =
        (data[key] as? String) ?: defValue

    override fun getInt(key: String, defValue: Int): Int =
        (data[key] as? Number)?.toInt() ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        (data[key] as? Number)?.toLong() ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        (data[key] as? Number)?.toFloat() ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        (data[key] as? Boolean) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        (data[key] as? Set<String>)?.toSet() ?: defValues

    override fun contains(key: String): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val staged = LinkedHashMap<String, Any?>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            staged[key] = value
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            staged[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            staged[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            staged[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            staged[key] = value
            return this
        }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            staged[key] = values?.toSet()
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            staged[key] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun apply() {
            commit()
        }

        override fun commit(): Boolean {
            val changedKeys = mutableListOf<String>()
            synchronized(this@FileSharedPreferences) {
                if (clearAll) {
                    changedKeys.addAll(data.keys)
                    data.clear()
                }
                for ((k, v) in staged) {
                    if (v == null) {
                        if (data.remove(k) != null) changedKeys.add(k)
                    } else {
                        val old = data.put(k, v)
                        if (old != v) changedKeys.add(k)
                    }
                }
                saveToDisk()
            }
            for (k in changedKeys) {
                listeners.forEach { it.onSharedPreferenceChanged(this@FileSharedPreferences, k) }
            }
            return true
        }
    }
}

open class Context {
    companion object {
        const val MODE_PRIVATE = 0
        const val CLIPBOARD_SERVICE = "clipboard"
        const val AUDIO_SERVICE = "audio"
        const val NOTIFICATION_SERVICE = "notification"
        const val POWER_SERVICE = "power"
        const val BLUETOOTH_SERVICE = "bluetooth"

        val rootDir: File by lazy {
            val custom = System.getProperty("enghub.home")
            val dir = if (!custom.isNullOrBlank()) File(custom) else File(System.getProperty("user.home"), ".enghub")
            dir.apply { mkdirs() }
        }

        private val prefsCache = ConcurrentHashMap<String, SharedPreferences>()
        private val clipboard = ClipboardManager()
        private val audioManager = AudioManager()
        private val notificationManager = NotificationManager()
        private val powerManager = PowerManager()
        private val bluetoothManager = BluetoothManager()
        private val activeServices = ConcurrentHashMap<Class<*>, Service>()
    }

    open val applicationContext: Context get() = Application.instance
    open val packageName: String get() = "com.hdlee73.englishstudy"
    open val packageManager: PackageManager = PackageManager()
    open val assets: AssetManager = AssetManager()
    open val resources: Resources = Resources()
    open val contentResolver: ContentResolver = ContentResolver()

    open val filesDir: File
        get() = File(rootDir, "files").apply { mkdirs() }

    open val cacheDir: File
        get() = File(rootDir, "cache").apply { mkdirs() }

    open fun getDatabasePath(name: String): File =
        File(File(rootDir, "databases").apply { mkdirs() }, name)

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefsCache.computeIfAbsent(name) {
            val prefsDir = File(rootDir, "prefs").apply { mkdirs() }
            FileSharedPreferences(File(prefsDir, "$name.json"))
        }

    open fun getSystemService(name: String): Any? = when (name) {
        CLIPBOARD_SERVICE -> clipboard
        AUDIO_SERVICE -> audioManager
        NOTIFICATION_SERVICE -> notificationManager
        POWER_SERVICE -> powerManager
        BLUETOOTH_SERVICE -> bluetoothManager
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    open fun <T> getSystemService(serviceClass: Class<T>): T = when (serviceClass) {
        ClipboardManager::class.java -> clipboard as T
        AudioManager::class.java -> audioManager as T
        NotificationManager::class.java -> notificationManager as T
        PowerManager::class.java -> powerManager as T
        BluetoothManager::class.java -> bluetoothManager as T
        else -> serviceClass.getDeclaredConstructor().newInstance()
    }

    open fun startService(intent: Intent): ComponentName? {
        val cls = intent.targetClass ?: return null
        val service = activeServices.computeIfAbsent(cls) {
            (cls.getDeclaredConstructor().newInstance() as Service).also { it.onCreate() }
        }
        service.onStartCommand(intent, 0, 1)
        return ComponentName(this, cls)
    }

    open fun startForegroundService(intent: Intent): ComponentName? = startService(intent)

    open fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
        val cls = intent.targetClass ?: return false
        val service = activeServices.computeIfAbsent(cls) {
            (cls.getDeclaredConstructor().newInstance() as Service).also { it.onCreate() }
        }
        val binder = service.onBind(intent)
        conn.onServiceConnected(ComponentName(this, cls), binder)
        return true
    }

    open fun unbindService(conn: ServiceConnection) {}

    open fun startActivity(intent: Intent) {
        runCatching {
            val uri = intent.data
            if (uri != null) {
                val raw = uri.toString()
                if (raw.startsWith("http://") || raw.startsWith("https://")) {
                    if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(raw))
                } else {
                    val file = File(uri.path ?: raw)
                    if (file.exists() && Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(file)
                    }
                }
            }
        }
    }

    open fun getColor(id: Int): Int = 0xFF172B2A.toInt()
}

open class ContextWrapper(val baseContext: Context = Application.instance) : Context()
