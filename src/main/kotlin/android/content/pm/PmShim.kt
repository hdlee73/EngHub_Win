package android.content.pm

import android.content.Intent

/** The app version written into app.properties by the Gradle build (build.gradle.kts: appVersion). */
object AppVersion {
    val name: String by lazy {
        runCatching {
            AppVersion::class.java.getResourceAsStream("/app.properties")?.use { java.util.Properties().apply { load(it) }.getProperty("version") }
        }.getOrNull()?.takeIf { it.isNotBlank() && !it.contains("$") } ?: "0"
    }
}

class PackageInfo {
    var versionName: String = AppVersion.name
    /** 1.32.0 -> 13200: only has to differ between versions (cached databases are keyed on it). */
    var versionCode: Int = AppVersion.name.split('.').map { it.toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 0 } * 10000 + it.getOrElse(1) { 0 } * 100 + it.getOrElse(2) { 0 }) }
    var longVersionCode: Long = versionCode.toLong()
}

class ResolveInfo

class PackageManager {
    companion object {
        const val PERMISSION_GRANTED = 0
        const val PERMISSION_DENIED = -1
        const val MATCH_DEFAULT_ONLY = 0x00010000
    }

    fun getPackageInfo(packageName: String, flags: Int): PackageInfo = PackageInfo()
    fun queryIntentActivities(intent: Intent, flags: Int): List<ResolveInfo> = emptyList()
    fun canRequestPackageInstalls(): Boolean = true
    fun getLaunchIntentForPackage(packageName: String): Intent? = null
}

object ServiceInfo {
    const val FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK = 2
    const val FOREGROUND_SERVICE_TYPE_DATA_SYNC = 1
    const val FOREGROUND_SERVICE_TYPE_MICROPHONE = 128
}
