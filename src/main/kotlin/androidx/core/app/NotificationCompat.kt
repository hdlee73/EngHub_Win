package androidx.core.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Context

object NotificationCompat {
    class Builder(private val context: Context, private val channelId: String = "") {
        private val notification = Notification()
        fun setSmallIcon(icon: Int): Builder = this
        fun setContentTitle(title: CharSequence?): Builder { notification.title = title; return this }
        fun setContentText(text: CharSequence?): Builder { notification.text = text; return this }
        fun setOngoing(ongoing: Boolean): Builder = this
        fun setOnlyAlertOnce(onlyAlertOnce: Boolean): Builder = this
        fun setAutoCancel(autoCancel: Boolean): Builder { notification.autoCancel = autoCancel; return this }
        fun setContentIntent(intent: PendingIntent?): Builder = this
        fun setProgress(max: Int, progress: Int, indeterminate: Boolean): Builder = this
        fun addAction(icon: Int, title: CharSequence?, intent: PendingIntent?): Builder = this
        fun build(): Notification = notification
    }
}
