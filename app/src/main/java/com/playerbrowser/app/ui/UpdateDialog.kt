package com.playerbrowser.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.playerbrowser.app.BuildConfig
import com.playerbrowser.app.update.UpdateState

@Composable
fun UpdateDialog(
    state: UpdateState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        is UpdateState.Idle, is UpdateState.Checking -> Unit
        is UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("확인") } },
            title = { Text("최신 버전입니다") },
            text = { Text("현재 버전 ${BuildConfig.VERSION_NAME}이(가) 최신입니다.") }
        )
        is UpdateState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
            title = { Text("업데이트 오류") },
            text = { Text(state.message) }
        )
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDownload) { Text("업데이트") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("나중에") } },
            title = { Text("새 버전 ${state.info.versionName}") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "현재: ${BuildConfig.VERSION_NAME}  →  새 버전: ${state.info.versionName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.info.releaseNotes.isNotBlank()) {
                        Text(state.info.releaseNotes)
                    }
                    if (state.info.apkSize > 0) {
                        Text(
                            "다운로드 크기: ${formatBytes(state.info.apkSize)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            confirmButton = { TextButton(onClick = onCancelDownload) { Text("취소") } },
            title = { Text("다운로드 중 ${state.info.versionName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                    )
                    val totalText = if (state.info.apkSize > 0) formatBytes(state.info.apkSize) else "?"
                    Text(
                        "${formatBytes(state.received)} / $totalText  (${(state.progress * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
        is UpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onInstall) { Text("설치") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
            title = { Text("설치 준비 완료") },
            text = {
                Text(
                    "버전 ${state.info.versionName} 다운로드 완료. " +
                        "설치를 누르면 시스템 설치 화면이 표시됩니다. " +
                        "처음이라면 '이 출처에서 앱 설치' 권한을 한 번 허용하셔야 합니다."
                )
            }
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
