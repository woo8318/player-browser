package com.playerbrowser.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

internal val GROUP_COLORS: List<Color> = listOf(
    Color(0xFF1976D2), // blue
    Color(0xFF388E3C), // green
    Color(0xFFF57C00), // orange
    Color(0xFFD32F2F), // red
    Color(0xFF7B1FA2), // purple
    Color(0xFF00838F), // cyan
    Color(0xFF5D4037), // brown
    Color(0xFF455A64)  // blue grey
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TabSwitcherOverlay(
    tabs: List<TabState>,
    groups: List<TabGroup>,
    activeTabId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onCloseMany: (List<String>) -> Unit,
    onMoveToGroup: (List<String>, String?) -> Unit,
    onAddGroup: (String, Int) -> String,
    onRenameGroup: (String, String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onMoveGroup: (String, Boolean) -> Unit,
    onNewTab: () -> Unit,
    onDismiss: () -> Unit
) {
    // Selection mode is mutually exclusive with normal browsing — once any
    // tab is selected, taps toggle selection instead of activating the tab.
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val inSelectMode by remember { derivedStateOf { selectedIds.isNotEmpty() } }
    var renamingGroupId by remember { mutableStateOf<String?>(null) }
    var newGroupDialog by remember { mutableStateOf(false) }
    // Holds the tab ids that should land in the next-picked group. Lets the
    // single-card move menu and the select-mode action bar share one picker
    // dialog + one "create then move" flow.
    var pendingMoveTargets by remember { mutableStateOf<List<String>?>(null) }

    val dragState = remember { TabDragState() }
    val gridState = rememberLazyGridState()
    val haptics = LocalHapticFeedback.current
    dragState.edgeZonePx = with(LocalDensity.current) { 64.dp.toPx() }
    val isDragging = dragState.draggingIds != null

    // Safety net: if startTransfer silently failed (no ACTION_DRAG_STARTED ever
    // arrives), undo the optimistic dragging state instead of leaving the
    // switcher stuck with a dimmed card.
    LaunchedEffect(isDragging) {
        if (isDragging) {
            delay(1_000)
            if (!dragState.sessionStarted) dragState.reset()
        }
    }

    // Edge auto-scroll while a card is being dragged near the top/bottom of the grid.
    LaunchedEffect(dragState, gridState) {
        snapshotFlow { dragState.autoScrollDirection }.collectLatest { direction ->
            if (direction == 0) return@collectLatest
            while (true) {
                gridState.scrollBy(direction * 24f)
                delay(16)
            }
        }
    }

    // Make sure the selection set never holds stale ids after a bulk-close.
    LaunchedEffect(tabs) {
        val live = tabs.map { it.id }.toSet()
        if (selectedIds.any { it !in live }) {
            selectedIds = selectedIds.intersect(live)
        }
    }

    BackHandler(enabled = true) {
        if (inSelectMode) selectedIds = emptySet()
        else onDismiss()
    }

    Scaffold(
        topBar = {
            if (inSelectMode) {
                TopAppBar(
                    title = { Text("${selectedIds.size}개 선택") },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = "cancel selection")
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            selectedIds = tabs.map { it.id }.toSet()
                        }) { Text("전체") }
                        IconButton(onClick = { pendingMoveTargets = selectedIds.toList() }) {
                            Icon(Icons.Filled.Folder, contentDescription = "move to group")
                        }
                        IconButton(onClick = {
                            onCloseMany(selectedIds.toList())
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "close selected")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("탭 ${tabs.size}") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "close switcher")
                        }
                    },
                    actions = {
                        IconButton(onClick = { newGroupDialog = true }) {
                            Icon(Icons.Filled.Folder, contentDescription = "new group")
                        }
                        IconButton(onClick = onNewTab) {
                            Icon(Icons.Filled.Add, contentDescription = "new tab")
                        }
                    }
                )
            }
        }
    ) { padding ->
        // While dragging, the empty "no group" section stays visible so a tab can
        // always be dragged OUT of its group, even when every tab is grouped.
        val groupedSections = remember(tabs, groups, isDragging) {
            buildGroupedSections(tabs, groups, includeEmptyUngrouped = isDragging)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onGloballyPositioned { dragState.gridBounds = it.boundsInRoot() }
                .tabDropTargetRoot(dragState) { ids, sectionKey ->
                    onMoveToGroup(ids, sectionKey.takeUnless { it == UNGROUPED_SECTION_KEY })
                    selectedIds = emptySet()
                }
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                groupedSections.forEach { section ->
                    val sectionKey = section.group?.id ?: UNGROUPED_SECTION_KEY
                    if (section.group != null || section.tabs.isNotEmpty() || isDragging) {
                        item(
                            span = { GridItemSpan(maxLineSpan) },
                            key = "header-${section.group?.id ?: "none"}"
                        ) {
                            GroupHeader(
                                group = section.group,
                                tabCount = section.tabs.size,
                                isDropHover = dragState.hoverSectionKey == sectionKey,
                                modifier = Modifier.tabDropRegion(
                                    dragState = dragState,
                                    regionId = "header-$sectionKey",
                                    sectionKey = sectionKey
                                ),
                                onRename = if (section.group != null) {
                                    { renamingGroupId = section.group.id }
                                } else null,
                                onDelete = if (section.group != null) {
                                    { onDeleteGroup(section.group.id) }
                                } else null,
                                onMoveUp = if (section.group != null &&
                                    groups.indexOfFirst { it.id == section.group.id } > 0
                                ) {
                                    { onMoveGroup(section.group.id, true) }
                                } else null,
                                onMoveDown = if (section.group != null &&
                                    groups.indexOfFirst { it.id == section.group.id }
                                        .let { it in 0 until groups.lastIndex }
                                ) {
                                    { onMoveGroup(section.group.id, false) }
                                } else null,
                                onCloseAll = if (section.tabs.isNotEmpty()) {
                                    { onCloseMany(section.tabs.map { it.id }) }
                                } else null
                            )
                        }
                    }
                    items(items = section.tabs, key = { it.id }) { tab ->
                        val selected = tab.id in selectedIds
                        TabCard(
                            tab = tab,
                            isActive = tab.id == activeTabId,
                            isSelected = selected,
                            inSelectMode = inSelectMode,
                            isDropHover = dragState.hoverSectionKey == sectionKey,
                            isBeingDragged = dragState.draggingIds?.contains(tab.id) == true,
                            modifier = Modifier.tabDropRegion(
                                dragState = dragState,
                                regionId = "tab-${tab.id}",
                                sectionKey = sectionKey
                            ),
                            dragSourceModifier = Modifier.tabDragSource(
                                haptics = haptics,
                                // Dragging a selected card carries the whole selection;
                                // the long press that precedes the drag has already
                                // added this tab to the set.
                                draggedIds = { (selectedIds + tab.id).toList() },
                                onTransferStarted = { ids -> dragState.draggingIds = ids }
                            ),
                            onSelect = {
                                if (inSelectMode) {
                                    selectedIds = if (selected) selectedIds - tab.id
                                    else selectedIds + tab.id
                                } else {
                                    onSelect(tab.id)
                                }
                            },
                            onLongPress = {
                                // Long press always enters/extends selection.
                                selectedIds = selectedIds + tab.id
                            },
                            onClose = {
                                if (inSelectMode && selected) {
                                    selectedIds = selectedIds - tab.id
                                }
                                onClose(tab.id)
                            },
                            onMoveRequest = { pendingMoveTargets = listOf(tab.id) },
                            onRemoveFromGroup = if (tab.groupId != null) {
                                { onMoveToGroup(listOf(tab.id), null) }
                            } else null
                        )
                    }
                }
            }
        }
    }

    val movingTargets = pendingMoveTargets
    if (movingTargets != null && !newGroupDialog) {
        GroupPickerDialog(
            groups = groups,
            onPick = { groupId ->
                onMoveToGroup(movingTargets, groupId)
                pendingMoveTargets = null
                if (inSelectMode) selectedIds = emptySet()
            },
            onCreateAndPick = {
                // Keep pendingMoveTargets set — NewGroupDialog will consume it
                // after the new group is created, so the move happens atomically
                // from the user's POV.
                newGroupDialog = true
            },
            onDismiss = { pendingMoveTargets = null }
        )
    }

    if (newGroupDialog) {
        NewGroupDialog(
            onCreate = { name, color ->
                val id = onAddGroup(name, color)
                newGroupDialog = false
                val targets = pendingMoveTargets
                if (targets != null) {
                    onMoveToGroup(targets, id)
                    pendingMoveTargets = null
                    if (inSelectMode) selectedIds = emptySet()
                }
            },
            onDismiss = {
                newGroupDialog = false
                // Top-bar "new group" entry leaves pendingMoveTargets null, so
                // this clear is a no-op there; for the move-and-create flow it
                // cancels the pending move if the user backs out.
                pendingMoveTargets = null
            }
        )
    }

    val renaming = renamingGroupId
    if (renaming != null) {
        val target = groups.firstOrNull { it.id == renaming }
        if (target != null) {
            RenameGroupDialog(
                initial = target.name,
                onRename = { name ->
                    onRenameGroup(renaming, name)
                    renamingGroupId = null
                },
                onDismiss = { renamingGroupId = null }
            )
        } else {
            renamingGroupId = null
        }
    }
}

private data class TabSection(
    val group: TabGroup?,
    val tabs: List<TabState>
)

private fun buildGroupedSections(
    tabs: List<TabState>,
    groups: List<TabGroup>,
    includeEmptyUngrouped: Boolean = false
): List<TabSection> {
    val byGroup = tabs.groupBy { it.groupId }
    val sections = mutableListOf<TabSection>()
    groups.forEach { g ->
        sections.add(TabSection(group = g, tabs = byGroup[g.id].orEmpty()))
    }
    val ungrouped = byGroup[null].orEmpty()
    if (ungrouped.isNotEmpty() || groups.isEmpty() || includeEmptyUngrouped) {
        sections.add(TabSection(group = null, tabs = ungrouped))
    }
    return sections
}

@Composable
private fun GroupHeader(
    group: TabGroup?,
    tabCount: Int,
    isDropHover: Boolean,
    modifier: Modifier = Modifier,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onCloseAll: (() -> Unit)?
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = if (isDropHover) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(top = 4.dp, bottom = 4.dp, start = if (isDropHover) 6.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (group != null) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(Color(group.color), CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        } else {
            Text(
                text = "그룹 없음",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = "$tabCount",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (onRename != null || onDelete != null || onMoveUp != null ||
            onMoveDown != null || onCloseAll != null
        ) {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "group menu",
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    onMoveUp?.let {
                        DropdownMenuItem(
                            text = { Text("위로 이동") },
                            leadingIcon = {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                            },
                            onClick = { menuOpen = false; it() }
                        )
                    }
                    onMoveDown?.let {
                        DropdownMenuItem(
                            text = { Text("아래로 이동") },
                            leadingIcon = {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                            },
                            onClick = { menuOpen = false; it() }
                        )
                    }
                    onRename?.let {
                        DropdownMenuItem(
                            text = { Text("이름 바꾸기") },
                            onClick = { menuOpen = false; it() }
                        )
                    }
                    onCloseAll?.let {
                        DropdownMenuItem(
                            text = { Text("이 그룹의 탭 모두 닫기") },
                            onClick = { menuOpen = false; it() }
                        )
                    }
                    onDelete?.let {
                        DropdownMenuItem(
                            text = { Text("그룹 해제 (탭 유지)") },
                            onClick = { menuOpen = false; it() }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabCard(
    tab: TabState,
    isActive: Boolean,
    isSelected: Boolean,
    inSelectMode: Boolean,
    isDropHover: Boolean,
    isBeingDragged: Boolean,
    modifier: Modifier = Modifier,
    dragSourceModifier: Modifier = Modifier,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    onClose: () -> Unit,
    onMoveRequest: () -> Unit,
    onRemoveFromGroup: (() -> Unit)?
) {
    val border = when {
        isDropHover -> MaterialTheme.colorScheme.secondary
        isSelected -> MaterialTheme.colorScheme.tertiary
        isActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        tonalElevation = if (isActive || isSelected) 4.dp else 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .alpha(if (isBeingDragged) 0.4f else 1f)
            .border(
                width = if (isActive || isSelected || isDropHover) 2.dp else 1.dp,
                color = border,
                shape = RoundedCornerShape(10.dp)
            )
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onLongPress
            )
            // Must come AFTER combinedClickable: the inner node wins the Main
            // pointer pass, so the drag detector consumes move events before the
            // clickable's post-long-press consumeUntilUp can cancel the drag.
            .then(dragSourceModifier)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (inSelectMode) {
                    Icon(
                        imageVector = if (isSelected) Icons.Filled.CheckCircle
                        else Icons.Outlined.Circle,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = tab.currentTitle.ifBlank { "새 탭" },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (!inSelectMode) {
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "tab menu",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("그룹으로 이동...") },
                                onClick = {
                                    menuOpen = false
                                    onMoveRequest()
                                }
                            )
                            if (onRemoveFromGroup != null) {
                                DropdownMenuItem(
                                    text = { Text("그룹에서 빼기") },
                                    onClick = {
                                        menuOpen = false
                                        onRemoveFromGroup()
                                    }
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "close tab",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                contentAlignment = Alignment.TopStart
            ) {
                Text(
                    text = tab.currentUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5
                )
            }
        }
    }
}

@Composable
private fun GroupPickerDialog(
    groups: List<TabGroup>,
    onPick: (String?) -> Unit,
    onCreateAndPick: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("그룹 선택") },
        text = {
            Column {
                TextButton(
                    onClick = { onPick(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("그룹 없음으로 이동", modifier = Modifier.weight(1f))
                }
                groups.forEach { g ->
                    TextButton(
                        onClick = { onPick(g.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(Color(g.color), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(g.name, modifier = Modifier.weight(1f))
                    }
                }
                TextButton(
                    onClick = onCreateAndPick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("새 그룹 만들어 이동", modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}

@Composable
private fun NewGroupDialog(
    onCreate: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(GROUP_COLORS.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 그룹") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("그룹 이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("색상", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    GROUP_COLORS.forEach { color ->
                        val isPick = color == selectedColor
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(color, CircleShape)
                                .border(
                                    width = if (isPick) 3.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = color }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val final = name.trim().ifBlank { "그룹" }
                    onCreate(final, selectedColor.toArgb())
                }
            ) { Text("만들기") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

@Composable
private fun RenameGroupDialog(
    initial: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("그룹 이름 바꾸기") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onRename(name.trim().ifBlank { "그룹" }) }) { Text("저장") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}
