package androidx.activity

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import java.util.concurrent.ConcurrentHashMap

private object ActivityLifecycle : Lifecycle() {
    override val currentState: Lifecycle.State = Lifecycle.State.RESUMED
    override fun addObserver(observer: LifecycleObserver) {}
    override fun removeObserver(observer: LifecycleObserver) {}
}

open class ComponentActivity : Activity(), LifecycleOwner {
    override val lifecycle: Lifecycle = ActivityLifecycle
    @PublishedApi
    internal val viewModelStoreMap = ConcurrentHashMap<Class<*>, ViewModel>()
    var contentComposable: (@androidx.compose.runtime.Composable () -> Unit)? = null

    open fun onCreate(savedInstanceState: Bundle?) {}
    open fun onStart() {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onStop() {}
    open fun onNewIntent(intent: android.content.Intent) {}
    open fun onWindowFocusChanged(hasFocus: Boolean) {}
    open fun dispatchKeyEvent(event: KeyEvent): Boolean = false
    open fun onDestroy() {}
}

fun ComponentActivity.enableEdgeToEdge() {}

inline fun <reified VM : ViewModel> ComponentActivity.viewModels(): Lazy<VM> = lazy {
    val cls = VM::class.java
    @Suppress("UNCHECKED_CAST")
    viewModelStoreMap.computeIfAbsent(cls) {
        if (AndroidViewModel::class.java.isAssignableFrom(cls)) {
            cls.getDeclaredConstructor(Application::class.java).newInstance(Application.instance)
        } else {
            cls.getDeclaredConstructor().newInstance()
        }
    } as VM
}
