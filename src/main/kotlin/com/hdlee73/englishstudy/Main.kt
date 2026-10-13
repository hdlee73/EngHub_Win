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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.io.File

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

fun main(args: Array<String>) {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable -> writeCrashLog("thread ${thread.name}", throwable) }
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
        val windowState = rememberWindowState(
        width = 520.dp,
        height = 900.dp,
        position = WindowPosition(Alignment.Center)
    )

    Window(
        onCloseRequest = {
            runCatching {
                com.hdlee73.englishstudy.listening.PlaybackService.instance.savePosition()
                activity.onPause()
                activity.onStop()
                activity.onDestroy()
            }
            exitApplication()
        },
        state = windowState,
        title = "EngHub",
        icon = appIcon,
        onPreviewKeyEvent = { awtKeyEvent ->
            if (awtKeyEvent.key == Key.Enter || awtKeyEvent.key == Key.NumPadEnter) {
                val action = if (awtKeyEvent.type == KeyEventType.KeyUp) KeyEvent.ACTION_UP else KeyEvent.ACTION_DOWN
                if (activity.dispatchKeyEvent(KeyEvent(action, KeyEvent.KEYCODE_ENTER))) {
                    return@Window true
                }
            }
            false
        }
    ) {
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




