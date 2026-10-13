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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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

fun main(args: Array<String>) {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        System.err.println("EngHub uncaught exception in thread ${thread.name}:")
        throwable.printStackTrace(System.err)
        runCatching {
            val logFile = File(System.getProperty("user.home"), "EngHub_crash.log")
            logFile.writeText("Thread: ${thread.name}\n${throwable.stackTraceToString()}\n")
        }
    }
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
        icon = painterResource("app_icon.png"),
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




