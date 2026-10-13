package androidx.activity.compose

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

fun ComponentActivity.setContent(content: @Composable () -> Unit) {
    this.contentComposable = content
}

/** Desktop stand-in for the Android back key: the Esc key (see Main.kt) calls the newest enabled [BackHandler]. */
object BackDispatcher {
    class Entry(val enabled: () -> Boolean, val onBack: () -> Unit)

    private val entries = java.util.concurrent.CopyOnWriteArrayList<Entry>()

    fun register(entry: Entry) { entries.add(entry) }
    fun unregister(entry: Entry) { entries.remove(entry) }

    /** Runs the newest enabled handler; false when nothing wants the key. */
    fun dispatch(): Boolean {
        val handler = entries.lastOrNull { it.enabled() } ?: return false
        handler.onBack()
        return true
    }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val currentEnabled = rememberUpdatedState(enabled)
    val currentOnBack = rememberUpdatedState(onBack)
    androidx.compose.runtime.DisposableEffect(Unit) {
        val entry = BackDispatcher.Entry({ currentEnabled.value }, { currentOnBack.value() })
        BackDispatcher.register(entry)
        onDispose { BackDispatcher.unregister(entry) }
    }
}

class ManagedActivityResultLauncher<I, O>(
    private val contract: ActivityResultContract<I, O>,
    private val onResult: (O) -> Unit
) {
    fun launch(input: I) {
        when (contract) {
            is ActivityResultContracts.RequestPermission -> {
                @Suppress("UNCHECKED_CAST")
                onResult(true as O)
            }
            is ActivityResultContracts.RequestMultiplePermissions -> {
                val perms = (input as? Array<*>)?.mapNotNull { it as? String }.orEmpty()
                @Suppress("UNCHECKED_CAST")
                onResult(perms.associateWith { true } as O)
            }
            is ActivityResultContracts.CreateDocument -> {
                val defaultName = (input as? String) ?: "output"
                Thread {
                    val dialog = FileDialog(null as Frame?, "파일 저장", FileDialog.SAVE)
                    dialog.file = defaultName
                    dialog.isVisible = true
                    val dir = dialog.directory
                    val file = dialog.file
                    val resultIntent = if (dir != null && file != null) {
                        Intent().setData(Uri.fromFile(File(dir, file)))
                    } else null
                    val code = if (resultIntent != null) Activity.RESULT_OK else Activity.RESULT_CANCELED
                    val parsed = contract.parseResult(code, resultIntent)
                    SwingUtilities.invokeLater { onResult(parsed) }
                }.apply { isDaemon = true; start() }
            }
            is ActivityResultContracts.OpenDocumentTree -> {
                Thread {
                    var chosen: File? = null
                    SwingUtilities.invokeAndWait {
                        val chooser = JFileChooser().apply {
                            dialogTitle = "폴더 선택"
                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                            isAcceptAllFileFilterUsed = false
                        }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            chosen = chooser.selectedFile
                        }
                    }
                    val resultIntent = chosen?.let { Intent().setData(Uri.fromFile(it)) }
                    val code = if (resultIntent != null) Activity.RESULT_OK else Activity.RESULT_CANCELED
                    val parsed = contract.parseResult(code, resultIntent)
                    SwingUtilities.invokeLater { onResult(parsed) }
                }.apply { isDaemon = true; start() }
            }
            else -> {
                val createdIntent = contract.createIntent(android.app.Application.instance, input)
                val multiple = createdIntent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                Thread {
                    val dialog = FileDialog(null as Frame?, "파일 선택", FileDialog.LOAD)
                    dialog.isMultipleMode = multiple
                    dialog.isVisible = true
                    val files = if (multiple) {
                        dialog.files?.toList().orEmpty()
                    } else {
                        val d = dialog.directory
                        val f = dialog.file
                        if (d != null && f != null) listOf(File(d, f)) else emptyList()
                    }
                    val resultIntent = if (files.isNotEmpty()) {
                        val uris = files.map { Uri.fromFile(it) }
                        Intent().apply {
                            data = uris.first()
                            clipData = ClipData.newUris(uris)
                        }
                    } else null
                    val code = if (resultIntent != null) Activity.RESULT_OK else Activity.RESULT_CANCELED
                    val parsed = contract.parseResult(code, resultIntent)
                    SwingUtilities.invokeLater { onResult(parsed) }
                }.apply { isDaemon = true; start() }
            }
        }
    }
}

@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit
): ManagedActivityResultLauncher<I, O> {
    val currentOnResult = rememberUpdatedState(onResult)
    return remember(contract) {
        ManagedActivityResultLauncher(contract) { currentOnResult.value(it) }
    }
}
