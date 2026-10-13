package androidx.compose.ui.platform

import android.app.Application
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

val LocalContext = staticCompositionLocalOf<Context> { Application.instance }
