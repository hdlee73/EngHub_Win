package androidx.lifecycle.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.StateFlow

private object DesktopLifecycle : Lifecycle() {
    override val currentState: Lifecycle.State = Lifecycle.State.RESUMED
    override fun addObserver(observer: LifecycleObserver) {}
    override fun removeObserver(observer: LifecycleObserver) {}
}

private object DesktopLifecycleOwner : LifecycleOwner {
    override val lifecycle: Lifecycle = DesktopLifecycle
}

val LocalLifecycleOwner = staticCompositionLocalOf<LifecycleOwner> { DesktopLifecycleOwner }

@Composable
fun <T> StateFlow<T>.collectAsStateWithLifecycle(): State<T> = collectAsState()
