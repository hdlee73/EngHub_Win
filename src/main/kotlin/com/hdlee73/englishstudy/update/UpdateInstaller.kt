package com.hdlee73.englishstudy.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads the Windows installer (.msi) of a newer release inside the app and runs it. The installer replaces the installed
 * version (same upgrade id), so the user only confirms once; EngHub closes itself first so no file is in use.
 */
object UpdateInstaller {
    private val client by lazy { OkHttpClient.Builder().callTimeout(10, TimeUnit.MINUTES).readTimeout(60, TimeUnit.SECONDS).build() }

    private fun dir(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }

    /** Downloads the release installer; [onProgress] gets 0..1 (or -1 when the size is unknown). Throws on failure. */
    suspend fun download(context: Context, release: UpdateChecker.Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val url = release.apkUrl ?: error("이 릴리스에는 설치 파일(.msi)이 없어요")
        val folder = dir(context)
        folder.listFiles()?.forEach { it.delete() }
        val target = File(folder, "EngHub-${release.version.removePrefix("v")}.msi")
        val partial = File(folder, "download.part")
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("다운로드에 실패했어요 (${response.code})")
            val body = response.body ?: error("다운로드에 실패했어요")
            val total = body.contentLength()
            var done = 0L
            body.byteStream().use { input ->
                partial.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onProgress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
            if (total > 0 && done != total) error("다운로드가 중간에 끊겼어요")
        }
        if (!partial.renameTo(target)) error("파일을 저장하지 못했어요")
        target
    }

    /** Windows needs no special permission to run an installer. */
    fun canInstall(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = true

    fun openInstallPermissionSettings(@Suppress("UNUSED_PARAMETER") context: Context) {}

    /** Starts the installer (it shows its own progress) and ends the app so the old files can be replaced. */
    fun install(@Suppress("UNUSED_PARAMETER") context: Context, msi: File) {
        ProcessBuilder("msiexec", "/i", msi.absolutePath, "/passive").start()
        Thread {
            Thread.sleep(800)
            System.exit(0)
        }.start()
    }
}
