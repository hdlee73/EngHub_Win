package android.content.pm

import android.content.Intent

class PackageInfo {
    var versionName: String = "1.31.1"
    var versionCode: Int = 57
    var longVersionCode: Long = 57L
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
