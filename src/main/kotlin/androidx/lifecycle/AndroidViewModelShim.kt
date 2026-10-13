package androidx.lifecycle

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

interface LifecycleObserver

abstract class Lifecycle {
    enum class Event {
        ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY, ON_ANY
    }

    enum class State {
        DESTROYED, INITIALIZED, CREATED, STARTED, RESUMED
    }

    abstract val currentState: State
    abstract fun addObserver(observer: LifecycleObserver)
    abstract fun removeObserver(observer: LifecycleObserver)
}

fun interface LifecycleEventObserver : LifecycleObserver {
    fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event)
}

interface LifecycleOwner {
    val lifecycle: Lifecycle
}

open class ViewModel {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    protected open fun onCleared() {}
    internal fun clearScope() {
        onCleared()
        scope.cancel()
    }
}

val ViewModel.viewModelScope: CoroutineScope
    get() = scope

open class AndroidViewModel(private val application: Application) : ViewModel() {
    @Suppress("UNCHECKED_CAST")
    fun <T : Application> getApplication(): T = application as T
}

val LifecycleOwner.lifecycleScope: CoroutineScope
    get() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
