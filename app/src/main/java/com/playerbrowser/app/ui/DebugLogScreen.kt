package com.playerbrowser.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playerbrowser.app.network.CrashRecorder
import com.playerbrowser.app.network.DebugLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by DebugLog.entries.collectAsState()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    // Crashes are loaded lazily on entry and after explicit refresh — they
    // don't change while the screen is open unless the user re-opens.
    var crashes by remember { mutableStateOf(CrashRecorder.list(context)) }
    var crashesExpanded by remember { mutableStateOf(false) }
    var selectedCrash by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("디버그 로그") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            CrashSection(
                crashes = crashes,
                expanded = crashesExpanded,
                onToggle = { crashesExpanded = !crashesExpanded },
                onSelect = { selectedCrash = it },
                onClear = {
                    CrashRecorder.clear(context)
                    crashes = emptyList()
                    crashesExpanded = false
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = { DebugLog.clear() }) { Text("지우기") }
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(entries.joinToString("\n")))
                    }
                ) { Text("전체 복사") }
                Text(
                    text = "${entries.size}줄",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp, top = 14.dp)
                )
            }

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "아직 로그가 없습니다.\n사이트를 한 번 열어본 뒤 다시 보세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF101010)),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(entries) { line ->
                        val color = when {
                            line.contains(" E/") -> Color(0xFFFF7070)
                            line.contains(" W/") -> Color(0xFFFFC960)
                            else -> Color(0xFFB8F0B0)
                        }
                        Text(
                            text = line,
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }

    selectedCrash?.let { file ->
        CrashDetailDialog(
            file = file,
            onDismiss = { selectedCrash = null },
            onCopy = { content ->
                clipboard.setText(AnnotatedString(content))
            }
        )
    }
}

@Composable
private fun CrashSection(
    crashes: List<File>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (File) -> Unit,
    onClear: () -> Unit
) {
    val color = if (crashes.isEmpty())
        MaterialTheme.colorScheme.surfaceVariant
    else
        Color(0xFF4A1F1F)

    Surface(
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = crashes.isNotEmpty(), onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (crashes.isEmpty()) "충돌 기록 없음" else "충돌 기록 ${crashes.size}건",
                    fontWeight = FontWeight.SemiBold,
                    color = if (crashes.isEmpty())
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        Color(0xFFFFD0D0),
                    modifier = Modifier.weight(1f)
                )
                if (crashes.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("삭제") }
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "collapse" else "expand",
                        tint = Color(0xFFFFD0D0)
                    )
                }
            }
            if (expanded && crashes.isNotEmpty()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    crashes.forEach { file ->
                        CrashRow(file = file, onClick = { onSelect(file) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashRow(file: File, onClick: () -> Unit) {
    val displayName = remember(file) {
        runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(file.lastModified()))
        }.getOrElse { file.name }
    }
    Surface(
        color = Color(0xFF2A0E0E),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(onClick = onClick)
    ) {
        Text(
            text = displayName,
            color = Color(0xFFFFE0E0),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrashDetailDialog(
    file: File,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit
) {
    val content = remember(file) { CrashRecorder.read(file) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF101010),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp, max = 600.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = file.name,
                        color = Color(0xFFFFD0D0),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onCopy(content) }) { Text("복사") }
                    TextButton(onClick = onDismiss) { Text("닫기") }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = content,
                        color = Color(0xFFFFB8B8),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
fun rememberDebugLogEntries() = remember { DebugLog.entries }
