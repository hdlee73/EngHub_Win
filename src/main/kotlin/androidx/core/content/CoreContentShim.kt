package androidx.core.content

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File

object ContextCompat {
    fun checkSelfPermission(context: Context, permission: String): Int =
        PackageManager.PERMISSION_GRANTED

    fun startForegroundService(context: Context, intent: Intent): ComponentName? =
        context.startForegroundService(intent)
}

object FileProvider {
    fun getUriForFile(context: Context, authority: String, file: File): Uri =
        Uri.fromFile(file)
}
