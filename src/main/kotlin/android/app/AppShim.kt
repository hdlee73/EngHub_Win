package android.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.IBinder

open class Application : Context() {
    companion object {
        val instance: Application = Application()
    }
    override val applicationContext: Context get() = this
}

open class Activity : ContextWrapper(Application.instance) {
    var intent: Intent = Intent()
    val isFinishing: Boolean = false
    val isDestroyed: Boolean = false
    val application: Application get() = Application.instance

    companion object {
        const val RESULT_OK = -1
        const val RESULT_CANCELED = 0
    }
}

fun Activity.setIntent(newIntent: Intent) {
    this.intent = newIntent
}

abstract class Service : ContextWrapper(Application.instance) {
    companion object {
        const val START_STICKY = 1
        const val START_NOT_STICKY = 2
        const val STOP_FOREGROUND_REMOVE = 1
    }

    open fun onCreate() {}
    open fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    abstract fun onBind(intent: Intent?): IBinder?
    open fun onDestroy() {}
    fun startForeground(id: Int, notification: Notification) {}
    fun startForeground(id: Int, notification: Notification, foregroundServiceType: Int) {}
    fun stopForeground(notificationBehavior: Int) {}
    fun stopSelf() {}
}

class Notification {
    class Builder(context: Context, channelId: String = "") {
        fun setSmallIcon(icon: Int): Builder = this
        fun setContentTitle(title: CharSequence?): Builder = this
        fun setContentText(text: CharSequence?): Builder = this
        fun setAutoCancel(autoCancel: Boolean): Builder = this
        fun setContentIntent(intent: PendingIntent?): Builder = this
        fun build(): Notification = Notification()
    }
}

class NotificationChannel(val id: String, val name: CharSequence, val importance: Int)

class NotificationManager {
    companion object {
        const val IMPORTANCE_DEFAULT = 3
        const val IMPORTANCE_LOW = 2
    }

    fun createNotificationChannel(channel: NotificationChannel) {}
    fun notify(id: Int, notification: Notification) {}
    fun cancel(id: Int) {}
    fun areNotificationsEnabled(): Boolean = true
}

class PendingIntent private constructor(val intent: Intent) {
    fun send() {
        Application.instance.startService(intent)
    }

    companion object {
        const val FLAG_IMMUTABLE = 1 shl 26
        const val FLAG_UPDATE_CURRENT = 1 shl 27

        fun getActivity(context: Context, requestCode: Int, intent: Intent, flags: Int): PendingIntent =
            PendingIntent(intent)

        fun getService(context: Context, requestCode: Int, intent: Intent, flags: Int): PendingIntent =
            PendingIntent(intent)
    }
}

open class AlertDialog {
    var isShowing: Boolean = false
        private set

    fun show(): AlertDialog {
        isShowing = true
        return this
    }

    fun dismiss() {
        isShowing = false
    }

    open class Builder(private val context: Context) {
        fun setTitle(title: CharSequence?): Builder = this
        fun setMessage(message: CharSequence?): Builder = this
        fun setPositiveButton(text: CharSequence?, listener: ((AlertDialog, Int) -> Unit)?): Builder = this
        fun setNegativeButton(text: CharSequence?, listener: ((AlertDialog, Int) -> Unit)?): Builder = this
        fun create(): AlertDialog = AlertDialog()
        fun show(): AlertDialog = AlertDialog().show()
    }
}
