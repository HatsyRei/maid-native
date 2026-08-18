package com.hatsyrei.maidnative.ui.chat

import android.net.Uri
import androidx.compose.runtime.Stable
import com.hatsyrei.maidnative.domain.Attachment

/**
 * Everything the chat screen can ask the view model to do.
 *
 * Passed as one remembered object rather than two dozen loose lambdas: a
 * function reference is a fresh instance on each recomposition, so the loose
 * form made every screen below here unskippable, and adding a single feature
 * meant editing four files' parameter lists.
 */
@Stable
class ChatActions(
    val submit: (String) -> Unit,
    val stop: () -> Unit,
    val attach: (Uri, Attachment.Kind) -> Unit,
    val removeAttachment: (Attachment) -> Unit,
    val saveAttachment: (Attachment, Uri) -> Unit,
    val newChat: () -> Unit,
    val openSettings: () -> Unit,
    val regenerate: (String) -> Unit,
    val deleteMessage: (String) -> Unit,
    val edit: (String, String, List<Attachment>) -> Unit,
    val revise: (String, String, List<Attachment>) -> Unit,
    val prevBranch: (String) -> Unit,
    val nextBranch: (String) -> Unit,
    val selectChat: (String) -> Unit,
    val renameChat: (String, String) -> Unit,
    val deleteChat: (String) -> Unit,
    val setSystemPrompt: (String) -> Unit,
    val selectModel: (String) -> Unit,
    val exportFileName: (String) -> String,
    val exportConversation: (String, Uri) -> Unit,
    val importConversations: (List<Uri>) -> Unit,
    val backupAllChats: (Uri) -> Unit,
)
