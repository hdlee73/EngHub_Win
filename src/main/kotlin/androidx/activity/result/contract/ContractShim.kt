package androidx.activity.result.contract

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

abstract class ActivityResultContract<I, O> {
    abstract fun createIntent(context: Context, input: I): Intent
    abstract fun parseResult(resultCode: Int, intent: Intent?): O
}

class ActivityResult(val resultCode: Int, val data: Intent?)

object ActivityResultContracts {
    class CreateDocument(val mimeType: String = "*/*") : ActivityResultContract<String, Uri?>() {
        override fun createIntent(context: Context, input: String): Intent =
            Intent("CREATE_DOCUMENT").putExtra("defaultName", input).setType(mimeType)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == Activity.RESULT_OK) intent?.data else null
    }

    class OpenDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).putExtra(Intent.EXTRA_MIME_TYPES, input)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == Activity.RESULT_OK) intent?.data else null
    }

    class OpenMultipleDocuments : ActivityResultContract<Array<String>, List<Uri>>() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).putExtra(Intent.EXTRA_MIME_TYPES, input).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

        override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
            if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
            val out = mutableListOf<Uri>()
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out.add(it) }
            }
            intent.data?.let { if (it !in out) out.add(it) }
            return out
        }
    }

    class OpenDocumentTree : ActivityResultContract<Uri?, Uri?>() {
        override fun createIntent(context: Context, input: Uri?): Intent =
            Intent("OPEN_DOCUMENT_TREE")

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == Activity.RESULT_OK) intent?.data else null
    }

    class RequestPermission : ActivityResultContract<String, Boolean>() {
        override fun createIntent(context: Context, input: String): Intent =
            Intent("REQUEST_PERMISSION").putExtra("permission", input)

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean = true
    }

    class RequestMultiplePermissions : ActivityResultContract<Array<String>, Map<String, Boolean>>() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            Intent("REQUEST_MULTIPLE_PERMISSIONS").putExtra("permissions", input)

        override fun parseResult(resultCode: Int, intent: Intent?): Map<String, Boolean> =
            intent?.getStringArrayExtra("permissions").orEmpty().associateWith { true }
    }

    class StartActivityForResult : ActivityResultContract<Intent, ActivityResult>() {
        override fun createIntent(context: Context, input: Intent): Intent = input
        override fun parseResult(resultCode: Int, intent: Intent?): ActivityResult =
            ActivityResult(resultCode, intent)
    }
}
