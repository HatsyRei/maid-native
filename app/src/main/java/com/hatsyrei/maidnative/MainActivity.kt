package com.hatsyrei.maidnative

import android.content.ComponentCallbacks2
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import com.hatsyrei.maidnative.ui.chat.ChatScreen
import com.hatsyrei.maidnative.ui.chat.ChatViewModel
import com.hatsyrei.maidnative.ui.markdown.clearMarkdownParseCache
import com.hatsyrei.maidnative.ui.settings.SettingsScreen
import com.hatsyrei.maidnative.ui.theme.LocalNameplate
import com.hatsyrei.maidnative.ui.theme.MaidNativeTheme
import com.hatsyrei.maidnative.ui.theme.rememberNameplate

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by viewModel.theme.collectAsState()
            MaidNativeTheme(theme) {
                CompositionLocalProvider(LocalNameplate provides rememberNameplate(theme)) {
                    MaidNativeApp(viewModel)
                }
            }
        }
    }

    /**
     * The markdown parse cache retains up to ~5 MB of AST and was otherwise only
     * dropped on a conversation switch, so a backgrounded app held it for no
     * benefit — raising the odds of being killed and paying a full cold start
     * (and a full reparse) later. Release it as soon as the system signals it
     * wants the memory back; the visible conversation reparses on demand.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) clearMarkdownParseCache()
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
                    onSystemPrompt = viewModel::setSystemPrompt,
                    onSelectModel = viewModel::setModel,
                    exportFileName = viewModel::exportFileName,
                    onExportConversation = viewModel::exportConversation,
                    onImportConversations = viewModel::importConversations,
                    onBackupAllChats = viewModel::backupAllChats,
                )

                Screen.Settings -> {
                    BackHandler { screen = Screen.Chat }
                    val presets by viewModel.presets.collectAsState()
                    SettingsScreen(
                        state = state,
                        presets = presets,
                        onBaseURL = viewModel::setBaseURL,
                        onApiKey = viewModel::setApiKey,
                        onModel = viewModel::setModel,
                        onRefreshModels = viewModel::refreshModels,
                        onReasoning = viewModel::setReasoning,
                        onScan = viewModel::scanEndpoint,
                        onResetScan = viewModel::resetScan,
                        onSavePreset = viewModel::savePreset,
                        onApplyPreset = viewModel::applyPreset,
                        onRenamePreset = viewModel::renamePreset,
                        onDeletePreset = viewModel::deletePreset,
                        onAccentColor = viewModel::setAccentColor,
                        onNameplate = viewModel::setNameplate,
                        onImportNameplate = viewModel::importNameplate,
                        onBack = { screen = Screen.Chat },
                    )
                }
            }
        }
    }
}
