package com.playerbrowser.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.playerbrowser.app.network.DebugLog
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

    /**
     * 받은 APK 를 **우리 앱이 직접 설치한다** (v1.3.66).
     *
     * 예전엔 `FileProvider` + `ACTION_VIEW` 로 APK 파일을 시스템 "패키지 설치
     * 관리자" 앱에 넘겼다 — 그래서 (1) 설치를 다른 프로그램이 대신 해주는
     * 것처럼 보이고 (2) 인텐트를 받을 수 있는 앱이 여럿이면 선택 창이 뜨고
     * (3) 성공/실패를 우리가 전혀 모른다. `PackageInstaller` 세션은 APK
     * 바이트를 우리가 세션에 직접 써넣고 `commit()` 하므로 파일이 밖으로
     * 나가지 않고 결과가 [UpdateInstallReceiver] 로 돌아온다.
     *
     * **완전 무음 설치는 일반 앱에 허용되지 않는다** — 시스템 확인 창은
     * 여전히 뜬다(그건 OS 보안 모델이라 우회 대상이 아니다). 다만 Android 12+
     * 에서 *자기 자신을 업데이트*하고 현재 설치된 앱을 심은 installer 가
     * 우리일 때는 `USER_ACTION_NOT_REQUIRED` 가 받아들여져 확인 없이 설치된다.
     * 조건이 안 맞으면 시스템이 조용히 `STATUS_PENDING_USER_ACTION` 을 돌려주고
     * 리시버가 확인 창을 띄우므로 어느 쪽이든 안전하다. 즉 이 방식으로 한 번
     * 설치가 되고 나면 그다음 업데이트부터 확인 창이 사라질 수 있다.
     *
     * 세션 생성/쓰기가 실패하면 종전 인텐트 방식으로 폴백해 업데이트 경로
     * 자체가 막히는 일은 없게 한다.
     */
    fun install(apk: File) {
        val ok = runCatching { installViaSession(apk) }.getOrElse { e ->
            DebugLog.w(TAG, "설치 세션 실패 → 인텐트 폴백: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        if (!ok) runCatching { launchInstaller(apk) }.onFailure { e ->
            DebugLog.w(TAG, "설치 인텐트도 실패: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun installViaSession(apk: File): Boolean {
        if (!apk.isFile || apk.length() <= 0L) {
            DebugLog.w(TAG, "설치할 APK 가 없거나 비어 있음: ${apk.name}")
            return false
        }

        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        // 자기 자신 업데이트임을 명시 — 시스템이 서명 동일성을 확인한다.
        params.setAppPackageName(context.packageName)
        runCatching { params.setSize(apk.length()) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                params.setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                )
            }
        }

        val sessionId = pi.createSession(params)
        var committed = false
        try {
            pi.openSession(sessionId).use { session ->
                session.openWrite(ENTRY_NAME, 0, apk.length()).use { out ->
                    apk.inputStream().use { input -> input.copyTo(out, 128 * 1024) }
                    out.flush()
                    session.fsync(out)
                }
                session.commit(resultSender(sessionId))
                committed = true
            }
        } finally {
            if (!committed) runCatching { pi.abandonSession(sessionId) }
        }
        DebugLog.d(TAG, "설치 세션 commit: id=$sessionId, ${apk.length()} bytes")
        return true
    }

    private fun resultSender(sessionId: Int): IntentSender {
        val intent = Intent(context, UpdateInstallReceiver::class.java)
            .setAction(UpdateInstallReceiver.ACTION_RESULT)
            .setPackage(context.packageName)
        // 시스템이 결과 extras 를 채워 넣으므로 MUTABLE 이어야 한다.
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    /** 세션 설치가 불가능할 때의 폴백 — 시스템 설치 관리자에게 파일을 넘긴다. */
    fun launchInstaller(apk: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    companion object {
        private const val TAG = "Update"
        private const val ENTRY_NAME = "player-browser-update"
    }
}

sealed class DownloadStep {
    data class Progress(val received: Long, val total: Long, val fraction: Float) : DownloadStep()
    data class Done(val file: File) : DownloadStep()
}
