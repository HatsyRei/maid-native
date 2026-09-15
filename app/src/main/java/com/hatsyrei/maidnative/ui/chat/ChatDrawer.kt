package com.hatsyrei.maidnative.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.domain.ConversationDefaults
import com.hatsyrei.maidnative.domain.tree.MessageNode
import com.hatsyrei.maidnative.ui.icons.AddIcon
import com.hatsyrei.maidnative.ui.icons.FileDownloadIcon
import com.hatsyrei.maidnative.ui.icons.FolderOpenIcon
import com.hatsyrei.maidnative.ui.icons.InfoOutlineIcon
import com.hatsyrei.maidnative.ui.icons.SaveAltIcon

// The inset is what makes the fade safe: it guarantees the list can always be
// scrolled far enough for the last entry to clear the gradient, so the two must
// stay equal — a taller fade would dim that entry permanently.
private val LIST_BOTTOM_INSET = 48.dp
private val LIST_FADE_HEIGHT = 48.dp

private val MENU_ITEM_PADDING = PaddingValues(horizontal = 20.dp, vertical = 0.dp)
private val MENU_ICON_GAP = 8.dp

internal fun chatTitle(node: MessageNode): String =
    (node.metadata["title"] as? String)?.takeIf { it.isNotBlank() } ?: ConversationDefaults.CHAT_TITLE

/**
 * Makes a surface inert for any gesture that *begins* while [blocked] holds, by
 * swallowing it on the Initial pass before any child (or the drawer's own
 * `anchoredDraggable` parent) can claim it. Acting on a surface that is still
 * moving is how taps land on the wrong chat and how menus end up riding the sheet.
 *
 * The decision is latched at DOWN and the gesture is then held to the lift, which
 * is the only shape that matches the framework: `PointerInputEventProcessor` hit
 * tests solely on the down transition, so a node cannot join a gesture already in
 * flight — and one that stops consuming hands it straight back, since a drag node
 * parked in `AwaitGesturePickup` re-arms the moment consumption ends. A gate that
 * is added and removed by recomposition therefore leaks the tail of any gesture
 * that outlives the block; this one never detaches and never re-evaluates.
 */
internal fun Modifier.inertWhile(blocked: () -> Boolean): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!blocked()) return@awaitEachGesture
            down.consume()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }

/**
 * Confines the drawer's open-swipe to the left [fraction] of the screen.
 *
 * `ModalNavigationDrawer` exposes no way to narrow its drag region — the
 * `anchoredDraggable` sits on its root and covers everything — so this sits on the
 * content, between that root and the chat, and simply enters the same touch-slop
 * race as everyone else, for gestures that begin past the threshold. Depth decides
 * the ties, because the Main pass runs descendant-to-ancestor: the list is below
 * this node and claims a vertical (or 45°) drag first, this node is below the
 * drawer and claims a horizontal one first. So a diagonal swipe from the right
 * still scrolls exactly as it would have; only the drawer is cut out.
 *
 * The slop must match the drawer's own — bidding higher would just hand it the
 * gesture. Once claimed, every change is consumed for the rest of the gesture:
 * `DragGestureNode` parks a stolen gesture in `AwaitGesturePickup` and re-arms the
 * moment consumption stops, so letting up would reopen the door.
 */
internal fun Modifier.restrictDrawerOpenDrag(enabled: () -> Boolean, fraction: Float = 0.5f): Modifier =
    pointerInput(fraction) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!enabled() || down.position.x < size.width * fraction) return@awaitEachGesture
            awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                ?: return@awaitEachGesture
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                change.consume()
            }
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DrawerContent(
    roots: List<MessageNode>,
    activeRoot: String?,
    settled: () -> Boolean,
    onSelect: (String) -> Unit,
    onNewChat: () -> Unit,
    onRename: (String, String) -> Unit,
    onDeleteChat: (String) -> Unit,
    onExport: (String) -> Unit,
    onProperties: (String, String) -> Unit,
    onImport: () -> Unit,
    onBackupAll: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .inertWhile { !settled() },
        drawerContainerColor = Color.Black,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Chats",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    DrawerActionChip("New chat", AddIcon, onClick = onNewChat)
                    DrawerOverflowMenu(onImport = onImport, onBackupAll = onBackupAll)
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = LIST_BOTTOM_INSET),
                ) {
                    if (roots.isEmpty()) {
                        item {
                            Text(
                                text = "No chats yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                    items(roots, key = { it.id }) { root ->
                        val title = chatTitle(root)
                        DrawerChatItem(
                            id = root.id,
                            title = title,
                            selected = root.id == activeRoot,
                            onClick = { onSelect(root.id) },
                            onRename = { onRename(root.id, title) },
                            onDelete = { onDeleteChat(root.id) },
                            onExport = { onExport(root.id) },
                            onProperties = { onProperties(root.id, title) },
                        )
                    }
                }
            }

            // Ramp to the sheet's own colour rather than masking alpha: the
            // container is literal black, so the two composite identically and
            // this costs one quad instead of an offscreen layer per frame.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(LIST_FADE_HEIGHT)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))),
            )
        }
    }
}

/**
 * Fully rounded rather than the chip default, so it reads as part of the
 * drawer's pill language instead of a settings control that wandered in.
 */
@Composable
private fun DrawerActionChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        shape = RoundedCornerShape(percent = 50),
    )
}

/**
 * Import and export-all are rare next to starting a chat, and spelling all three
 * out side by side is what made the header wrap. An overflow glyph is honest where
 * the old bespoke ones were not: it promises only "more here", and the actions
 * behind it are named in words.
 */
@Composable
private fun DrawerOverflowMenu(onImport: () -> Unit, onBackupAll: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More chat actions")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Import") },
                trailingIcon = {
                    Icon(
                        FolderOpenIcon,
                        contentDescription = null,
                        modifier = Modifier.padding(start = MENU_ICON_GAP),
                    )
                },
                contentPadding = MENU_ITEM_PADDING,
                onClick = {
                    open = false
                    onImport()
                },
            )
            DropdownMenuItem(
                text = { Text("Export all") },
                trailingIcon = {
                    Icon(
                        SaveAltIcon,
                        contentDescription = null,
                        modifier = Modifier.padding(start = MENU_ICON_GAP),
                    )
                },
                contentPadding = MENU_ITEM_PADDING,
                onClick = {
                    open = false
                    onBackupAll()
                },
            )
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
    onProperties: () -> Unit,
) {
    val menu = rememberTapMenu(remember(id) { "chat:$id" })
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
                    .tapMenuGestures(menu, onTap = onClick)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelLarge,
                    // Dim just the label while pressed as the long-press cue.
                    color = if (menu.pressed) pillTextColor.copy(alpha = 0.5f) else pillTextColor,
                )
            }
        }
        TapContextMenu(
            expanded = menu.expanded,
            touchOffset = menu.touchOffset,
            onDismiss = menu::close,
        ) {
            MenuOption(
                text = "Rename",
                trailingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = { menu.close(); onRename() },
            )
            MenuOption(
                text = "Export",
                trailingIcon = { Icon(FileDownloadIcon, contentDescription = null) },
                onClick = { menu.close(); onExport() },
            )
            MenuOption(
                text = "Properties",
                trailingIcon = { Icon(InfoOutlineIcon, contentDescription = null) },
                onClick = { menu.close(); onProperties() },
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
                onClick = { menu.close(); onDelete() },
            )
        }
    }
}
