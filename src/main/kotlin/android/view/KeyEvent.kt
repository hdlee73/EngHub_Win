package android.view

class KeyEvent(
    val action: Int = ACTION_DOWN,
    val keyCode: Int = 0,
    val isCanceled: Boolean = false
) {
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val KEYCODE_VOLUME_UP = 24
        const val KEYCODE_VOLUME_DOWN = 25
        const val KEYCODE_ENTER = 66
        const val KEYCODE_NUMPAD_ENTER = 160
    }
}
