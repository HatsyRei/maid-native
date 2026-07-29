package com.hatsyrei.maidnative

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import com.hatsyrei.maidnative.ui.chat.ChatScreen
import com.hatsyrei.maidnative.ui.chat.ChatViewModel
import com.hatsyrei.maidnative.ui.settings.SettingsScreen
import com.hatsyrei.maidnative.ui.theme.MaidNativeTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaidNativeTheme {
                MaidNativeApp(viewModel)
            }
        }
    }
}

private enum class Screen { Chat, Settings }

@Composable
private fun MaidNativeApp(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    var screen by remember { mutableStateOf(Screen.Chat) }

    // `AnimatedContent` disposes the outgoing screen once the transition ends,
    // which would otherwise discard everything the screen held in
    // `rememberSaveable` — most visibly the chat list's scroll position, since
    // `rememberLazyListState` is saveable-backed. Providing a
    // `SaveableStateHolder` per screen keeps that state across the swap.
    val screenState = rememberSaveableStateHolder()

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            // Settings slides in from the right; going back slides it away to the
            // right while the chat eases back in from a slight left offset.
            if (targetState == Screen.Settings) {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 5 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 5 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "screen",
    ) { target ->
        // Keyed by name rather than the enum itself: the holder's saved map is
        // written into the activity's Bundle, so its keys must be types the
        // Bundle can store.
        screenState.SaveableStateProvider(target.name) {
            when (target) {
                Screen.Chat -> ChatScreen(
                    state = state,
                    onSubmit = viewModel::submit,
                    onStop = viewModel::stop,
                    onNewChat = viewModel::newChat,
                    onOpenSettings = { screen = Screen.Settings },
                    onRegenerate = viewModel::regenerate,
                    onDelete = viewModel::deleteMessage,
                    onEdit = viewModel::editMessage,
                    onRevise = viewModel::revise,
                    onPrevBranch = viewModel::prevBranch,
                    onNextBranch = viewModel::nextBranch,
                    onSelectChat = viewModel::selectChat,
                    onRenameChat = viewModel::renameChat,
                    onDeleteChat = viewModel::deleteChat,
                    onSelectModel = viewModel::setModel,
                    exportFileName = viewModel::exportFileName,
                    onExportConversation = viewModel::exportConversation,
                    onImportConversations = viewModel::importConversations,
                    onBackupAllChats = viewModel::backupAllChats,
                )

                Screen.Settings -> {
                    BackHandler { screen = Screen.Chat }
                    SettingsScreen(
                        state = state,
                        onBaseURL = viewModel::setBaseURL,
                        onApiKey = viewModel::setApiKey,
                        onModel = viewModel::setModel,
                        onRefreshModels = viewModel::refreshModels,
                        onScan = viewModel::scanEndpoint,
                        onResetScan = viewModel::resetScan,
                        onBack = { screen = Screen.Chat },
                    )
                }
            }
        }
    }
}
