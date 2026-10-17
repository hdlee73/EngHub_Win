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
    internal var title: CharSequence? = null
    internal var text: CharSequence? = null
    internal var autoCancel: Boolean = false

    class Builder(context: Context, channelId: String = "") {
        private val notification = Notification()
        fun setSmallIcon(icon: Int): Builder = this
        fun setContentTitle(title: CharSequence?): Builder { notification.title = title; return this }
        fun setContentText(text: CharSequence?): Builder { notification.text = text; return this }
        fun setAutoCancel(autoCancel: Boolean): Builder { notification.autoCancel = autoCancel; return this }
        fun setContentIntent(intent: PendingIntent?): Builder = this
        fun build(): Notification = notification
    }
}

class NotificationChannel(val id: String, val name: CharSequence, val importance: Int)

private var registeredMainWindow: java.awt.Window? = null

/**
 * 진단용 이벤트 기록. 파일/폴더 선택창과 종료 흐름을 남겨서 "앱이 저절로 꺼진다/멈춘 것 같다"의 원인을 찾는다.
 * (`%USERPROFILE%\EngHub_events.log`)
 */
fun logEngHubEvent(tag: String, detail: String = "") {
    runCatching {
        java.io.File(System.getProperty("user.home"), "EngHub_events.log")
            .appendText("[${java.time.LocalDateTime.now()}] $tag $detail\n")
    }
}

/** Compose 창이 만들어지면 자신을 등록한다. 파일/폴더 선택창과 안내창의 부모로 쓴다. */
fun rememberMainWindow(window: java.awt.Window) {
    registeredMainWindow = window
}

/**
 * 앱의 메인 창(있으면). 파일/폴더 선택창이나 안내창을 부모 없이(null) 띄우면 그 창이 메인 창 *뒤*로 깔린 채
 * 모달로 입력만 막아서, 사용자에게는 "앱이 멈췄다"로 보인다(실제 신고). 부모를 넘겨 주면 항상 위에 뜬다.
 * 아직 등록 전(아주 이른 시점)이면 보이는 Frame 을 찾아 쓴다.
 */
fun engHubMainWindow(): java.awt.Window? = registeredMainWindow ?: runCatching {
    val frames = java.awt.Frame.getFrames().orEmpty().filter { it.isVisible }
    frames.firstOrNull { it.title == "EngHub" } ?: frames.firstOrNull()
}.getOrNull()

/**
 * Shows Android-style notifications in the Windows notification area (AWT SystemTray). The tray icon is also the
 * app's tray menu; [installTray] lets the app supply that menu. Previously [notify] was an empty no-op while
 * [areNotificationsEnabled] always returned true, so update notices were silently dropped yet marked as seen.
 */
class NotificationManager {
    companion object {
        const val IMPORTANCE_DEFAULT = 3
        const val IMPORTANCE_LOW = 2
    }

    private var trayIcon: java.awt.TrayIcon? = null
    private var trayAttempted = false

    /**
     * Installs (once) the notification-area icon. [menu] entries are `label to action`; [onOpen] runs when the
     * icon is double-clicked. Returns true when the tray is available.
     */
    @Synchronized
    fun installTray(tooltip: String, menu: List<Pair<String, () -> Unit>>, onOpen: (() -> Unit)?): Boolean {
        if (trayIcon != null) return true
        if (!java.awt.SystemTray.isSupported()) { trayAttempted = true; return false }
        return runCatching {
            val icon = java.awt.TrayIcon(trayImage(), tooltip)
            icon.isImageAutoSize = true
            val popup = java.awt.PopupMenu()
            for ((label, action) in menu) {
                popup.add(java.awt.MenuItem(label).apply { addActionListener { action() } })
            }
            if (onOpen != null) {
                icon.addActionListener { onOpen() }  // double-click
            }
            if (menu.isNotEmpty() || onOpen != null) icon.popupMenu = popup
            java.awt.SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            true
        }.getOrDefault(false).also { trayAttempted = true }
    }

    fun notify(id: Int, notification: Notification) {
        ensureTray()
        val title = notification.title?.toString()?.takeIf { it.isNotBlank() } ?: "EngHub"
        val body = notification.text?.toString().orEmpty()
        runCatching { trayIcon?.displayMessage(title, body, java.awt.TrayIcon.MessageType.INFO) }
    }

    fun cancel(id: Int) {}

    fun createNotificationChannel(channel: NotificationChannel) {}

    /** True when a notification can actually be shown (the tray icon is installed). */
    fun areNotificationsEnabled(): Boolean {
        ensureTray()
        return trayIcon != null
    }

    private fun ensureTray() {
        if (!trayAttempted) installTray("EngHub", emptyList(), null)
    }

    private fun trayImage(): java.awt.Image {
        val size = 16
        val image = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        runCatching {
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = java.awt.Color(0x0C, 0x6F, 0x67)
            g.fillRoundRect(0, 0, size, size, 6, 6)
            g.color = java.awt.Color.WHITE
            g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 11)
            g.drawString("E", 4, 12)
        }
        g.dispose()
        return image
    }
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
    internal var titleText: String? = null
    internal var messageText: String? = null
    internal var positiveText: String? = null
    internal var positiveListener: ((AlertDialog, Int) -> Unit)? = null
    internal var negativeText: String? = null
    internal var negativeListener: ((AlertDialog, Int) -> Unit)? = null

    var isShowing: Boolean = false
        private set

    /**
     * Shows a real Windows dialog. The old stub only flipped [isShowing] without rendering anything, so TTS error
     * dialogs (and their "음성 설정" action) were invisible.
     */
    fun show(): AlertDialog {
        val run = Runnable { showNow() }
        if (javax.swing.SwingUtilities.isEventDispatchThread()) run.run()
        else runCatching { javax.swing.SwingUtilities.invokeAndWait(run) }.onFailure { run.run() }
        return this
    }

    private fun showNow() {
        if (titleText == null && messageText == null) {
            isShowing = true
            return
        }
        isShowing = true
        val options = listOfNotNull(positiveText ?: "확인", negativeText?.takeIf { it.isNotBlank() })
        val choice = runCatching {
            javax.swing.JOptionPane.showOptionDialog(
                android.app.engHubMainWindow(), messageText, titleText ?: "EngHub",
                javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.INFORMATION_MESSAGE,
                null, options.toTypedArray(), options.first()
            )
        }.getOrDefault(javax.swing.JOptionPane.CLOSED_OPTION)
        isShowing = false
        when (choice) {
            0 -> positiveListener?.invoke(this, -1)
            1 -> negativeListener?.invoke(this, -2)
        }
    }

    fun dismiss() {
        isShowing = false
    }

    open class Builder(private val context: Context) {
        private val dialog = AlertDialog()
        fun setTitle(title: CharSequence?): Builder { dialog.titleText = title?.toString(); return this }
        fun setMessage(message: CharSequence?): Builder { dialog.messageText = message?.toString(); return this }
        fun setPositiveButton(text: CharSequence?, listener: ((AlertDialog, Int) -> Unit)?): Builder {
            dialog.positiveText = text?.toString()
            dialog.positiveListener = listener
            return this
        }
        fun setNegativeButton(text: CharSequence?, listener: ((AlertDialog, Int) -> Unit)?): Builder {
            dialog.negativeText = text?.toString()
            dialog.negativeListener = listener
            return this
        }
        fun create(): AlertDialog = dialog
        fun show(): AlertDialog = dialog.show()
    }
}
