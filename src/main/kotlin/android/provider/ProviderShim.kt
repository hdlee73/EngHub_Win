package android.provider

import android.content.ContentResolver
import android.net.Uri
import java.io.File

object OpenableColumns {
    const val DISPLAY_NAME = "_display_name"
    const val SIZE = "_size"
}

object MediaStore {
    object MediaColumns {
        const val DISPLAY_NAME = "_display_name"
        const val MIME_TYPE = "mime_type"
        const val RELATIVE_PATH = "relative_path"
        const val IS_PENDING = "is_pending"
    }

    object Downloads {
        val EXTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/external/downloads")
    }
}

object DocumentsContract {
    object Document {
        const val COLUMN_DOCUMENT_ID = "document_id"
        const val COLUMN_DISPLAY_NAME = "_display_name"
        const val COLUMN_MIME_TYPE = "mime_type"
        const val MIME_TYPE_DIR = "vnd.android.document/directory"
    }

    fun getTreeDocumentId(documentUri: Uri): String =
        documentUri.path ?: documentUri.toString()

    fun getDocumentId(documentUri: Uri): String =
        documentUri.path ?: documentUri.toString()

    fun buildTreeDocumentUri(authority: String?, documentId: String): Uri =
        Uri.fromFile(File(documentId))

    fun buildDocumentUriUsingTree(treeUri: Uri, documentId: String): Uri =
        Uri.fromFile(File(documentId))

    fun buildChildDocumentsUriUsingTree(treeUri: Uri, parentDocumentId: String): Uri =
        Uri.parse("content://children/${Uri.fromFile(File(parentDocumentId)).path}")

    fun createDocument(
        content: ContentResolver,
        parentDocumentUri: Uri,
        mimeType: String,
        displayName: String
    ): Uri? {
        val parentDir = File(parentDocumentUri.path ?: return null)
        parentDir.mkdirs()
        var target = File(parentDir, displayName)
        var counter = 1
        val base = displayName.substringBeforeLast('.', displayName)
        val ext = displayName.substringAfterLast('.', "")
        while (target.exists()) {
            val nextName = if (ext.isNotEmpty()) "$base ($counter).$ext" else "$base ($counter)"
            target = File(parentDir, nextName)
            counter++
        }
        target.createNewFile()
        return Uri.fromFile(target)
    }

    fun deleteDocument(content: ContentResolver, documentUri: Uri): Boolean {
        val f = File(documentUri.path ?: return false)
        return f.delete()
    }
}

object Settings {
    const val ACTION_MANAGE_UNKNOWN_APP_SOURCES = "android.settings.MANAGE_UNKNOWN_APP_SOURCES"
    const val ACTION_APPLICATION_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS"
}
