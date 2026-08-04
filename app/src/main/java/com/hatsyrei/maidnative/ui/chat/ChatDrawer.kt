package com.hatsyrei.maidnative.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.domain.ConversationDefaults
import com.hatsyrei.maidnative.domain.tree.MessageNode
import com.hatsyrei.maidnative.ui.icons.FileDownloadIcon
import com.hatsyrei.maidnative.ui.icons.FolderOpenIcon
import com.hatsyrei.maidnative.ui.icons.SaveAltIcon

internal fun chatTitle(node: MessageNode): String =
    (node.metadata["title"] as? String)?.takeIf { it.isNotBlank() } ?: ConversationDefaults.CHAT_TITLE

/**
 * Swallows every touch on the Initial pass, before any child (or the drawer's own
 * `anchoredDraggable` parent) can claim it. Used to make the sheet inert while it
 * is mid-drag or mid-animation: acting on a surface that is still moving is how
 * taps land on the wrong chat and how context menus end up riding the sheet.
 */
private fun Modifier.blockPointerInput(blocked: Boolean): Modifier =
    if (!blocked) {
        this
    } else {
        pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DrawerContent(
    roots: List<MessageNode>,
    activeRoot: String?,
    interactive: Boolean,
    onSelect: (String) -> Unit,
    onNewChat: () -> Unit,
    onRename: (String) -> Unit,
    onDeleteChat: (String) -> Unit,
    onExport: (String) -> Unit,
    onImport: () -> Unit,
    onBackupAll: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .blockPointerInput(blocked = !interactive),
        drawerContainerColor = Color.Black,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Chats",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onImport, modifier = Modifier.size(40.dp)) {
                Icon(FolderOpenIcon, contentDescription = "Import conversations")
            }
            IconButton(onClick = onBackupAll, modifier = Modifier.size(40.dp)) {
                Icon(SaveAltIcon, contentDescription = "Back up all chats")
            }
            IconButton(onClick = onNewChat, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "New chat")
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            if (roots.isEmpty()) {
                item {
                    Text(
                        text = "No conversations yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(roots, key = { it.id }) { root ->
                DrawerChatItem(
                    id = root.id,
                    title = chatTitle(root),
                    selected = root.id == activeRoot,
                    onClick = { onSelect(root.id) },
                    onRename = { onRename(root.id) },
                    onDelete = { onDeleteChat(root.id) },
                    onExport = { onExport(root.id) },
                )
            }
        }
    }
}

@Composable
private fun DrawerChatItem(
    id: String,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    val menus = LocalMenuController.current
    val menuId = remember(id) { "chat:$id" }
    // `derivedStateOf` so this pill recomposes when *its* menu toggles, not every
    // time any other item's does.
    val menuOpen by remember(menuId) { derivedStateOf { menus.openId == menuId } }
    val closeMenu = { menus.close(menuId) }
    DisposableEffect(menuId) { onDispose { menus.close(menuId) } }
    var pressOffset by remember { mutableStateOf(Offset.Zero) }
    var pressed by remember { mutableStateOf(false) }
    // Fade the pill between focused and unfocused states (RN parity).
    val pillColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        animationSpec = tween(450),
        label = "chatPillBg",
    )
    val pillTextColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(450),
        label = "chatPillText",
    )
    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = pillColor,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                tryAwaitRelease()
                                pressed = false
                            },
                            onTap = { onClick() },
                            onLongPress = { pressOffset = it; menus.open(menuId) },
                        )
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelLarge,
                    // Dim just the label while pressed as the long-press cue.
                    color = if (pressed) pillTextColor.copy(alpha = 0.5f) else pillTextColor,
                )
            }
        }
        TapContextMenu(
            expanded = menuOpen,
            touchOffset = pressOffset,
            onDismiss = closeMenu,
        ) {
            MenuOption(
                text = "Rename",
                trailingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = { closeMenu(); onRename() },
            )
            MenuOption(
                text = "Export",
                trailingIcon = { Icon(FileDownloadIcon, contentDescription = null) },
                onClick = { closeMenu(); onExport() },
            )
            MenuOption(
                text = "Delete",
                textColor = MaterialTheme.colorScheme.error,
                trailingIcon = {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = { closeMenu(); onDelete() },
            )
        }
    }
}
