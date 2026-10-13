package android.widget

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

class Toast private constructor(private val text: String) {
    fun show() {
        latestMessage.value = text to System.currentTimeMillis()
    }

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1
        val latestMessage = MutableStateFlow<Pair<String, Long>?>(null)

        fun makeText(context: Context, text: CharSequence, duration: Int): Toast =
            Toast(text.toString())
    }
}
