package com.playerbrowser.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import com.playerbrowser.app.network.DebugLog

/**
 * `PackageInstaller` 세션의 결과를 받는다 (v1.3.66).
 *
 * 예전 FileProvider + ACTION_VIEW 방식은 APK 를 시스템 "패키지 설치 관리자"
 * 앱에 넘기고 끝이라 성공/실패를 알 방법이 아예 없었다. 세션 방식은 커밋
 * 결과가 이 리시버로 돌아오므로, 실패하면 사용자에게 이유를 알려줄 수 있고
 * 시스템이 확인 창을 요구하면(STATUS_PENDING_USER_ACTION) 그 인텐트를
 * 우리가 직접 띄운다 — 중간에 다른 앱이 끼지 않는다.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESULT) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent)
                if (confirm == null) {
                    DebugLog.w(TAG, "설치 확인 인텐트가 없음")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }.onFailure {
                    DebugLog.w(TAG, "설치 확인 창 실행 실패: ${it.javaClass.simpleName}: ${it.message}")
                }
            }

            PackageInstaller.STATUS_SUCCESS ->
                DebugLog.d(TAG, "설치 성공 — 앱이 곧 재시작됨")

            // 사용자가 확인 창에서 취소한 경우. 조용히 넘긴다.
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                DebugLog.d(TAG, "설치 취소됨")

            else -> {
                DebugLog.w(TAG, "설치 실패(status=$status): $message")
                val why = message.ifBlank { "코드 $status" }
                runCatching { Toast.makeText(context, "설치 실패: $why", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        private const val TAG = "Update"
        const val ACTION_RESULT = "com.playerbrowser.app.INSTALL_RESULT"
    }
}
