package androidx.appcompat.app

import android.content.Context
import androidx.activity.ComponentActivity

open class AppCompatActivity : ComponentActivity()

open class AlertDialog : android.app.AlertDialog() {
    class Builder(context: Context) : android.app.AlertDialog.Builder(context)
}
