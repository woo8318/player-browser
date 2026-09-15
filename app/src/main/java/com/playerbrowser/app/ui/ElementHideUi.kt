package com.playerbrowser.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playerbrowser.app.data.ElementHideStore

/**
 * Bottom bar shown while the "요소 숨기기" picker (`element_picker.js`) is up.
 * The page overlay does the selecting; this bar only sends commands.
 */
@Composable
internal fun ElementPickBar(
    canUndo: Boolean,
    onWiden: () -> Unit,
    onNarrow: () -> Unit,
    onHide: () -> Unit,
    onUndo: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "숨길 영역을 끌거나 탭해서 고르세요",
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = onDone) { Text("완료") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Default 24dp side padding leaves "되돌리기" no room at 1/4 width.
                val pad = PaddingValues(horizontal = 4.dp)
                OutlinedButton(
                    onClick = onWiden,
                    modifier = Modifier.weight(1f),
                    contentPadding = pad
                ) {
                    Text("넓게", fontSize = 13.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onNarrow,
                    modifier = Modifier.weight(1f),
                    contentPadding = pad
                ) {
                    Text("좁게", fontSize = 13.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onUndo,
                    enabled = canUndo,
                    modifier = Modifier.weight(1f),
                    contentPadding = pad
                ) {
                    Text("되돌리기", fontSize = 13.sp, maxLines = 1)
                }
                Button(
                    onClick = onHide,
                    modifier = Modifier.weight(1f),
                    contentPadding = pad
                ) {
                    Text("숨기기", fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}

/**
 * "숨긴 요소 관리" — the site's rules with checkboxes. [onChanged] runs after
 * any release so the caller can re-apply the style sheet to the open page.
 */
internal fun showHiddenElementsDialog(context: Context, host: String, onChanged: () -> Unit) {
    val store = ElementHideStore.get(context)
    val rules = store.rules(host)
    if (rules.isEmpty()) {
        Toast.makeText(context, "이 사이트에 숨긴 요소가 없어요", Toast.LENGTH_SHORT).show()
        return
    }
    val labels = Array(rules.size) { i -> rules[i].label.ifBlank { rules[i].selector } }
    val checked = BooleanArray(rules.size)
    androidx.appcompat.app.AlertDialog.Builder(context)
        .setTitle("숨긴 요소 — $host")
        .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
            checked[which] = isChecked
        }
        .setPositiveButton("선택 해제") { _, _ ->
            val picked = rules.filterIndexed { i, _ -> checked[i] }.map { it.selector }
            if (picked.isEmpty()) {
                Toast.makeText(context, "해제할 항목을 고르세요", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            store.remove(host, picked)
            onChanged()
            Toast.makeText(context, "${picked.size}개를 다시 보이게 했어요", Toast.LENGTH_SHORT).show()
        }
        .setNeutralButton("모두 해제") { _, _ ->
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setMessage("이 사이트에서 숨긴 요소 ${rules.size}개를 모두 다시 보이게 할까요?")
                .setPositiveButton("모두 해제") { _, _ ->
                    store.clear(host)
                    onChanged()
                    Toast.makeText(context, "숨긴 요소를 모두 해제했어요", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }
        .setNegativeButton("닫기", null)
        .show()
}
