package com.hdlee73.englishstudy

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hdlee73.englishstudy.ui.AppTab
import com.hdlee73.englishstudy.ui.DesktopNav
import com.hdlee73.englishstudy.update.UpdateChecker
import kotlinx.coroutines.delay
import java.awt.Dimension
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.io.File

/** Design A: a desktop-sized window (not the phone-shaped 520x900 default), restored from the last session. */
private const val DEFAULT_WINDOW_WIDTH = 1100f
private const val DEFAULT_WINDOW_HEIGHT = 760f
private const val MIN_WINDOW_WIDTH = 940
private const val MIN_WINDOW_HEIGHT = 640

/** Ctrl+키 조합 단축키. Swing 메뉴 바로 만들면 앱 어디서나 Alt 없는 단축키로 동작한다. */
private fun ctrlAccelerator(keyCode: Int): javax.swing.KeyStroke =
    javax.swing.KeyStroke.getKeyStroke(keyCode, java.awt.event.KeyEvent.CTRL_DOWN_MASK)

/** Ctrl+1 .. Ctrl+9 로 탭을 바로 연다(메뉴 순서). */
private fun tabAccelerator(index: Int): javax.swing.KeyStroke =
    ctrlAccelerator(java.awt.event.KeyEvent.VK_1 + index.coerceIn(0, 8))

/** 클래스패스의 아이콘 PNG 를 읽는다. 어떤 이유로든 실패하면 null (기본 아이콘). */
private fun loadAppIcon(): Painter? = runCatching {
    val stream = sequenceOf("drawable/app_icon.png", "icon.png", "app_icon.png")
        .firstNotNullOfOrNull { Thread.currentThread().contextClassLoader?.getResourceAsStream(it) }
        ?: return@runCatching null
    stream.use { BitmapPainter(loadImageBitmap(it)) }
}.getOrNull()

/** 크래시 내용을 사용자 폴더의 EngHub_crash.log 에 남긴다. */
private fun writeCrashLog(where: String, throwable: Throwable) {
    System.err.println("EngHub exception ($where):")
    throwable.printStackTrace(System.err)
    runCatching {
        File(System.getProperty("user.home"), "EngHub_crash.log")
            .appendText("[$where] ${java.time.LocalDateTime.now()}\n${throwable.stackTraceToString()}\n")
    }
}

/** Opens a folder in the Windows file explorer. */
private fun openFolder(dir: File) {
    runCatching {
        dir.mkdirs()
        java.awt.Desktop.getDesktop().open(dir)
    }
}

/**
 * Windows 11 24H2(빌드 26100) 이후에는 레거시 음성 인식 엔진(SAPI SR)이 기본 포함되지 않아, 발음 평가와
 * 받아쓰기가 결과를 내지 못한다(사용자 PC: build 26300, 설치된 인식기 0개). 원인과 설치 방법을 창으로 안내한다.
 */
private fun showSpeechEngineHelp() {
    val installed = runCatching { android.speech.SpeechEngine.isInstalled }.getOrDefault(false)
    val text = buildString {
        appendLine(
            if (installed) "이 PC에는 Windows 음성 인식 엔진이 설치되어 있습니다."
            else "이 PC에는 Windows 음성 인식 엔진이 설치되어 있지 않습니다."
        )
        appendLine()
        appendLine("발음 평가(Speaking)와 받아쓰기(DocVoice)는 Windows 음성 인식 엔진을 사용합니다.")
        appendLine("Windows 11 24H2 이후 버전에는 이 엔진이 기본으로 들어 있지 않아 직접 설치해야 합니다.")
        appendLine()
        appendLine("[설치 방법 1] 설정 → 시간 및 언어 → 언어 및 지역 → English (United States)")
        appendLine("  → 언어 옵션 → '음성' 다운로드")
        appendLine()
        appendLine("[설치 방법 2] 위 항목이 없으면 Microsoft 다운로드 센터에서")
        appendLine("  'Microsoft Speech Platform - Runtime (Version 11, x64)' 와")
        appendLine("  'Microsoft Speech Platform - Runtime Languages (en-US)' 를 설치하세요.")
        appendLine()
        appendLine("설치한 뒤 EngHub 를 다시 실행하면 인식기를 찾습니다(엔진 없이는 녹음만 되고 글자는 나오지 않습니다).")
    }
    javax.swing.JOptionPane.showMessageDialog(
        android.app.engHubMainWindow(), text, "음성 인식 엔진 설치 안내", javax.swing.JOptionPane.INFORMATION_MESSAGE
    )
}

/**
 * 저장된 창 크기/위치를 화면 작업영역(작업 표시줄 제외) 안으로 맞춘다.
 *
 * 이전에 최대화 상태로 저장된 값은 화면보다 큰 크기와 음수 좌표를 갖기 때문에, 그대로 복원하면 창이 작업
 * 표시줄을 덮거나 화면 밖으로 나간다(사용자 신고). 그래서 (1) 창이 놓였던 모니터를 찾아 (2) 그 모니터의
 * 작업영역 크기로 줄이고 (3) 좌표를 작업영역 안으로 밀어 넣는다. 이렇게 하면 창은 항상 화면 안에 완전히
 * 들어온다.
 */
private fun restoredGeometry(saved: DesktopNav.WindowGeometry?): Quadruple = runCatching {
    val env = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
    val primary = primaryWorkArea(env)
    val savedRect = if (saved != null) {
        java.awt.Rectangle(
            Math.round(saved.x ?: 0f),
            Math.round(saved.y ?: 0f),
            Math.round(saved.width).coerceAtLeast(1),
            Math.round(saved.height).coerceAtLeast(1),
        )
    } else null
    // 저장된 창이 실제로 놓여 있던 모니터의 작업영역을 고른다(여러 모니터 대응).
    val wa = if (savedRect != null) workAreaOfBestMonitor(env, primary, savedRect) else primary
    val hasPosition = saved?.x != null && saved.y != null
    val clampedW = (saved?.width ?: DEFAULT_WINDOW_WIDTH).coerceAtMost(wa.width.toFloat())
    val clampedH = (saved?.height ?: DEFAULT_WINDOW_HEIGHT).coerceAtMost(wa.height.toFloat())
    val defaultX = wa.x + ((wa.width - clampedW) / 2f).coerceAtLeast(0f)
    val defaultY = wa.y + ((wa.height - clampedH) / 2f).coerceAtLeast(0f)
    // 크기를 이미 작업영역 이하로 줄였으므로 남은 여백 범위 안으로 좌표만 밀면 창이 완전히 들어온다.
    val maxX = wa.x + (wa.width - clampedW).coerceAtLeast(0f)
    val maxY = wa.y + (wa.height - clampedH).coerceAtLeast(0f)
    val x = if (hasPosition) (saved.x ?: defaultX).coerceIn(wa.x.toFloat(), maxX) else defaultX
    val y = if (hasPosition) (saved.y ?: defaultY).coerceIn(wa.y.toFloat(), maxY) else defaultY
    Quadruple(clampedW, clampedH, x, y)
}.getOrElse { Quadruple(DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT, 0f, 0f) }

/**
 * Opens a web page in the user's browser.
 */
private fun browse(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}

/** The saved window rect (width, height, x, y) — all values as Dp floats. */
private data class Quadruple(val width: Float, val height: Float, val x: Float, val y: Float)

/** 주 모니터의 작업영역(작업 표시줄 제외). */
private fun primaryWorkArea(env: java.awt.GraphicsEnvironment): java.awt.Rectangle {
    val cfg = env.screenDevices.firstOrNull()?.defaultConfiguration
    return cfg?.let(::workAreaOf) ?: java.awt.Rectangle(0, 0, 1024, 680)
}

/**
 * [rect] 와 가장 많이 겹치는 모니터의 작업영역. 어느 화면과도 겹치지 않으면 주 화면을 되돌린다.
 * 창의 일부만 다른 모니터에 걸쳐 있어도 사용자가 보던 화면을 고르도록, 꼭짓점이 아니라 겹치는 넓이로 판단한다.
 */
private fun workAreaOfBestMonitor(
    env: java.awt.GraphicsEnvironment,
    primary: java.awt.Rectangle,
    rect: java.awt.Rectangle,
): java.awt.Rectangle {
    var best = primary
    var bestArea = -1L
    for (device in env.screenDevices) {
        val cfg = device.defaultConfiguration ?: continue
        val overlap = (cfg.bounds.intersection(rect).let { it.width.toLong() * it.height.toLong() }).coerceAtLeast(0L)
        if (overlap > bestArea) {
            bestArea = overlap
            best = workAreaOf(cfg)
        }
    }
    return best
}

/** 모니터 하나의 작업영역(작업 표시줄 제외). */
private fun workAreaOf(cfg: java.awt.GraphicsConfiguration): java.awt.Rectangle {
    val bounds = cfg.bounds
    val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(cfg)
    return java.awt.Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        (bounds.width - insets.left - insets.right).coerceAtLeast(1),
        (bounds.height - insets.top - insets.bottom).coerceAtLeast(1),
    )
}

fun main(args: Array<String>) {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable -> writeCrashLog("thread ${thread.name}", throwable) }
    // 앱이 저절로 꺼지는 원인을 찾기 위한 종료 기록(진단용). 종료 시점의 창 상태와 AWT 스레드 위치를 남긴다.
    Runtime.getRuntime().addShutdownHook(Thread {
        android.app.logEngHubEvent(
            "jvm-exit",
            "windows=" + runCatching {
                java.awt.Window.getWindows().joinToString { "${it.javaClass.simpleName}(visible=${it.isVisible})" }
            }.getOrDefault("?")
        )
        val edt = Thread.getAllStackTraces().entries.firstOrNull { it.key.name.startsWith("AWT-EventQueue") }
        android.app.logEngHubEvent("jvm-exit-edt", edt?.value?.take(8)?.joinToString(" <- ").orEmpty())
    })
    // Windows 음성 인식 엔진이 있는지 앱 시작 직후 백그라운드에서 확인해 둔다(첫 인식 시 지연 방지).
    runCatching { android.speech.SpeechEngine.warmUp() }
    // Windows 기본 입력 장치가 소리를 내보내지 않는 PC 가 있어, 소리가 들어오는 마이크를 미리 골라 둔다.
    Thread({ runCatching { android.media.AudioDevices.probeAutoInput() } }, "EngHub-MicProbe").apply { isDaemon = true }.start()
    // 시작 중 예외가 나도 jpackage 런처가 "Failed to launch JVM" 으로만 보이지 않도록, 원인을 창으로 알려 준다.
    try {
        runApp(args)
    } catch (t: Throwable) {
        writeCrashLog("startup", t)
        runCatching {
            javax.swing.JOptionPane.showMessageDialog(
                null,
                "EngHub 를 시작하지 못했어요.\n\n${t::class.java.simpleName}: ${t.message}\n\n자세한 내용: ${File(System.getProperty("user.home"), "EngHub_crash.log")}",
                "EngHub",
                javax.swing.JOptionPane.ERROR_MESSAGE
            )
        }
        System.exit(1)
    }
}

private fun runApp(args: Array<String>) {
    application {
        val activity = remember {
            MainActivity().apply {
                if (args.isNotEmpty()) {
                    val f = File(args[0])
                    if (f.exists()) {
                        intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(f))
                    }
                }
                onCreate(null)
            }
        }

        // 창 아이콘: 리소스가 없거나 읽기 실패해도 앱 실행은 계속된다 (아이콘 로드 예외가 "Failed to launch JVM" 의 원인이었음).
        val appIcon = remember { loadAppIcon() }
        val restore = remember { DesktopNav.restoreWindow() }
        // 작업영역 클램프: 화면보다 크게 저장된 창(예: 최대화 제거 직후)도 작업 표시줄과 화면 밖에 걸치지 않게 한다.
        val geometry = remember { restoredGeometry(restore) }
        val windowState = rememberWindowState(
            width = geometry.width.dp,
            height = geometry.height.dp,
            position = WindowPosition(geometry.x.dp, geometry.y.dp)
        )
        var alwaysOnTop by remember { mutableStateOf(false) }

        fun saveGeometry() {
            val size = windowState.size
            val position = windowState.position
            DesktopNav.saveWindow(
                width = size.width.value,
                height = size.height.value,
                x = if (position.isSpecified) position.x.value else null,
                y = if (position.isSpecified) position.y.value else null,
                maximized = windowState.placement != WindowPlacement.Floating
            )
        }

        fun closeApp() {
            android.app.logEngHubEvent("close-app", Thread.currentThread().stackTrace.take(10).joinToString(" <- "))
            runCatching {
                com.hdlee73.englishstudy.listening.PlaybackService.instance.savePosition()
                activity.onPause()
                activity.onStop()
                activity.onDestroy()
            }
            runCatching { saveGeometry() }
            exitApplication()
        }

        // Reopen maximised if that is how the user left it, and remember the geometry from now on.
        LaunchedEffect(Unit) {
            if (restore?.maximized == true) windowState.placement = WindowPlacement.Maximized
        }
        LaunchedEffect(windowState.size, windowState.position, windowState.placement) { saveGeometry() }

        Window(
            onCloseRequest = { closeApp() },
            state = windowState,
            title = "EngHub",
            icon = appIcon,
            resizable = true,
            alwaysOnTop = alwaysOnTop,
            onPreviewKeyEvent = { keyEvent ->
                // Esc = Android's back key: close the open window/list first, then go to the previous menu.
                val awtKeyEvent = keyEvent.nativeKeyEvent as? java.awt.event.KeyEvent
                if (awtKeyEvent != null) {
                    if (awtKeyEvent.keyCode == java.awt.event.KeyEvent.VK_ESCAPE && awtKeyEvent.id == java.awt.event.KeyEvent.KEY_PRESSED) {
                        if (androidx.activity.compose.BackDispatcher.dispatch()) return@Window true
                    }
                    if (awtKeyEvent.keyCode == java.awt.event.KeyEvent.VK_ENTER) {
                        val action = if (keyEvent.type == KeyEventType.KeyUp) KeyEvent.ACTION_UP else KeyEvent.ACTION_DOWN
                        if (activity.dispatchKeyEvent(KeyEvent(action, KeyEvent.KEYCODE_ENTER))) {
                            return@Window true
                        }
                    }
                }
                false
            }
        ) {
            // The window can be made smaller than the sidebar + content, so keep a sane minimum.
            DisposableEffect(window) {
                // 파일/폴더 선택창과 안내창의 부모로 쓸 실제 창을 등록한다(부모가 없으면 창 뒤로 깔려 앱이 멈춘 것처럼 보임).
                android.app.rememberMainWindow(window)
                runCatching { window.minimumSize = Dimension(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT) }
                onDispose {}
            }

            // Native Windows menu bar (Design A). Swing's own JMenuBar is used instead of Compose's MenuBar
            // wrapper: the Compose pop-up left a blank icon gutter in front of every item and had no real
            // mnemonics/accelerators (user report), which a plain Swing menu bar draws like any Windows app.
            val menuFont = remember { java.awt.Font("Malgun Gothic", java.awt.Font.PLAIN, 12) }
            val alwaysOnTopItem = remember {
                javax.swing.JCheckBoxMenuItem("항상 위").apply {
                    font = menuFont
                    addActionListener { alwaysOnTop = isSelected }
                }
            }
            LaunchedEffect(alwaysOnTop) {
                if (alwaysOnTopItem.isSelected != alwaysOnTop) alwaysOnTopItem.isSelected = alwaysOnTop
            }
            DisposableEffect(window) {
                fun entry(text: String, accel: javax.swing.KeyStroke?, action: () -> Unit): javax.swing.JMenuItem =
                    javax.swing.JMenuItem(text).apply {
                        font = menuFont
                        if (accel != null) accelerator = accel
                        addActionListener { action() }
                    }

                fun menu(title: String, mnemonicKey: Int, fill: (javax.swing.JMenu) -> Unit) =
                    javax.swing.JMenu(title).apply {
                        font = menuFont
                        mnemonic = mnemonicKey
                        fill(this)
                    }

                val tabs = AppTab.values()
                val bar = javax.swing.JMenuBar()
                bar.add(
                    menu("파일", java.awt.event.KeyEvent.VK_F) { m ->
                        m.add(entry("저장 폴더 열기", null) { openFolder(android.content.Context.rootDir) })
                        m.add(javax.swing.JPopupMenu.Separator())
                        m.add(entry("종료", ctrlAccelerator(java.awt.event.KeyEvent.VK_Q)) { closeApp() })
                    }
                )
                bar.add(
                    menu("학습", java.awt.event.KeyEvent.VK_S) { m ->
                        tabs.take(6).forEachIndexed { index, target ->
                            m.add(entry(target.label, tabAccelerator(index)) { DesktopNav.select(target) })
                        }
                    }
                )
                bar.add(
                    menu("연습", java.awt.event.KeyEvent.VK_P) { m ->
                        tabs.drop(6).forEachIndexed { index, target ->
                            m.add(entry(target.label, tabAccelerator(index + 6)) { DesktopNav.select(target) })
                        }
                    }
                )
                bar.add(
                    menu("보기", java.awt.event.KeyEvent.VK_V) { m ->
                        m.add(alwaysOnTopItem)
                        m.add(
                            entry("창 크기 초기화", null) {
                                windowState.placement = WindowPlacement.Floating
                                windowState.size = DpSize(DEFAULT_WINDOW_WIDTH.dp, DEFAULT_WINDOW_HEIGHT.dp)
                                windowState.position = WindowPosition(Alignment.Center)
                            }
                        )
                    }
                )
                bar.add(
                    menu("도움말", java.awt.event.KeyEvent.VK_H) { m ->
                        m.add(
                            entry("단어장에서 검색", ctrlAccelerator(java.awt.event.KeyEvent.VK_K)) {
                                DesktopNav.select(AppTab.DICTIONARY)
                            }
                        )
                        m.add(entry("GitHub 릴리스", null) { browse(UpdateChecker.RELEASES_URL) })
                        m.add(entry("음성 인식 엔진 설치 안내", null) { showSpeechEngineHelp() })
                        m.add(javax.swing.JPopupMenu.Separator())
                        m.add(entry("앱 정보", null) { DesktopNav.requestAbout() })
                    }
                )
                window.jMenuBar = bar
                onDispose { window.jMenuBar = null }
            }

            // One tray icon for the whole app (also used for update notifications). Compose's own Tray is
            // deliberately not used, so notifications and the menu share a single icon.
            val notificationManager = remember {
                activity.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            }
            DisposableEffect(Unit) {
                val showWindow: () -> Unit = {
                    window.isVisible = true
                    window.toFront()
                }
                javax.swing.SwingUtilities.invokeLater {
                    notificationManager?.installTray(
                        tooltip = "EngHub",
                        menu = listOf(
                            "EngHub 열기" to showWindow,
                            "앱 정보" to { DesktopNav.requestAbout() },
                            "종료" to { closeApp() }
                        ),
                        onOpen = showWindow
                    )
                }
                onDispose {}
            }

            DisposableEffect(window) {
                val listener = object : WindowFocusListener {
                    override fun windowGainedFocus(e: WindowEvent?) {
                        activity.onWindowFocusChanged(true)
                    }

                    override fun windowLostFocus(e: WindowEvent?) {
                        activity.onWindowFocusChanged(false)
                    }
                }
                window.addWindowFocusListener(listener)
                onDispose {
                    window.removeWindowFocusListener(listener)
                }
            }

            CompositionLocalProvider(LocalContext provides activity) {
                Box(modifier = Modifier.fillMaxSize()) {
                    activity.contentComposable?.invoke()

                    // Global Toast overlay
                    val toastPair by Toast.latestMessage.collectAsState()
                    var visibleToast by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(toastPair) {
                        val p = toastPair ?: return@LaunchedEffect
                        visibleToast = p.first
                        delay(2800L)
                        if (Toast.latestMessage.value?.second == p.second) {
                            visibleToast = null
                        }
                    }

                    val msg = visibleToast
                    if (!msg.isNullOrBlank()) {
                        Surface(
                            color = Color(0xE6222629),
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 24.dp, vertical = 76.dp)
                        ) {
                            Text(
                                text = msg,
                                color = Color.White,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
