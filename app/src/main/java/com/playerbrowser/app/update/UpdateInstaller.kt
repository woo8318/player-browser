package com.playerbrowser.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class UpdateInstaller(private val context: Context) {

    /** Streams progress in [0f, 1f]; final emission is 1f along with the saved File. */
    fun download(info: UpdateInfo): Flow<DownloadStep> = flow {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Clean older APKs to avoid filling cache.
        dir.listFiles()?.forEach { if (it.extension.equals("apk", true)) it.delete() }

        val target = File(dir, "player-browser-${info.versionName}.apk")
        val url = URL(info.apkUrl)
        var conn = (url.openConnection() as HttpURLConnection)
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = info.apkSize.takeIf { it > 0 } ?: conn.contentLengthLong.coerceAtLeast(0L)

            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var received = 0L
                    var lastEmit = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        received += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 100 || (total > 0 && received >= total)) {
                            val frac = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
                            emit(DownloadStep.Progress(received, total, frac))
                            lastEmit = now
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        emit(DownloadStep.Done(target))
    }.flowOn(Dispatchers.IO)

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun launchInstallSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun launchInstaller(apk: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

sealed class DownloadStep {
    data class Progress(val received: Long, val total: Long, val fraction: Float) : DownloadStep()
    data class Done(val file: File) : DownloadStep()
}
