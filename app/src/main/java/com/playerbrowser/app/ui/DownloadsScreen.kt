package com.playerbrowser.app.ui

import android.webkit.CookieManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.playerbrowser.app.player.DownloadCenter
import com.playerbrowser.app.player.DownloadEntry
import com.playerbrowser.app.player.DownloadStatus
import com.playerbrowser.app.player.VideoPlayerActivity
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Downloaded / downloading videos. Media3's download index is a database, not an
 * observable, so progress is polled on a 1s tick while this screen is on top —
 * the same cadence the foreground notification updates at.
 */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(emptyList<DownloadEntry>()) }
    var usedBytes by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            entries = DownloadCenter.downloads(context)
            usedBytes = DownloadCenter.usedBytes(context)
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("다운로드")
                        if (usedBytes > 0) {
                            Text(
                                "저장 공간 ${formatBytes(usedBytes)} 사용 중",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "back")
                    }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "다운로드한 영상이 없습니다.\n영상을 길게 눌러 \"다운로드\"를 고르세요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(entries, key = { it.id }) { entry ->
                DownloadRow(
                    entry = entry,
                    onPlay = {
                        val cookie = runCatching {
                            CookieManager.getInstance().getCookie(entry.url)
                        }.getOrNull()
                        VideoPlayerActivity.start(
                            context,
                            entry.url,
                            entry.pageUrl.ifBlank { null },
                            cookie,
                            null,
                            DownloadCenter.mimeTypeFor(null, entry.url),
                            entry.title
                        )
                    },
                    onTogglePause = {
                        DownloadCenter.setPaused(context, entry.id, entry.status != DownloadStatus.PAUSED)
                    },
                    onRetry = { DownloadCenter.retry(context, entry.id) },
                    onDelete = {
                        DownloadCenter.remove(context, entry.id)
                        Toast.makeText(context, "삭제했어요", Toast.LENGTH_SHORT).show()
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DownloadRow(
    entry: DownloadEntry,
    onPlay: () -> Unit,
    onTogglePause: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.status == DownloadStatus.COMPLETED) { onPlay() }
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                statusLine(entry),
                style = MaterialTheme.typography.labelSmall,
                color = if (entry.status == DownloadStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.status == DownloadStatus.DOWNLOADING || entry.status == DownloadStatus.PAUSED) {
                LinearProgressIndicator(
                    progress = { (entry.percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            when (entry.status) {
                DownloadStatus.COMPLETED -> IconButton(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "재생")
                }
                DownloadStatus.FAILED -> IconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = "다시 시도")
                }
                DownloadStatus.PAUSED -> IconButton(onClick = onTogglePause) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "이어받기")
                }
                DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> IconButton(onClick = onTogglePause) {
                    Icon(Icons.Filled.Pause, contentDescription = "일시정지")
                }
                DownloadStatus.REMOVING -> Unit
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "삭제")
            }
        }
    }
}

private fun statusLine(entry: DownloadEntry): String = when (entry.status) {
    DownloadStatus.COMPLETED -> "완료 · ${formatBytes(entry.bytesDownloaded)} · 오프라인 재생"
    DownloadStatus.DOWNLOADING -> {
        val pct = entry.percent.toInt()
        if (entry.contentLength > 0) {
            "$pct% · ${formatBytes(entry.bytesDownloaded)} / ${formatBytes(entry.contentLength)}"
        } else {
            // HLS reports no total until every segment length is known.
            "받는 중 · ${formatBytes(entry.bytesDownloaded)}"
        }
    }
    DownloadStatus.PAUSED -> "일시정지 · ${formatBytes(entry.bytesDownloaded)}"
    DownloadStatus.QUEUED -> "대기 중"
    DownloadStatus.FAILED -> "실패 · 다시 시도할 수 있어요"
    DownloadStatus.REMOVING -> "삭제 중"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format(Locale.US, "%.1fGB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format(Locale.US, "%.0fMB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0fKB", bytes / 1024.0)
    else -> "${bytes}B"
}
