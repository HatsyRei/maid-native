package com.hatsyrei.maidnative.data.store

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Thin wrapper over the Storage Access Framework for reading/writing exported
 * conversation JSON. Keeps the `ContentResolver`/`DocumentsContract` plumbing
 * out of the view model, which retains the encode/decode and error handling.
 */
class ConversationFileStore(private val resolver: ContentResolver) {

    /** Overwrite the document at [uri] with [json]. */
    fun write(uri: Uri, json: String) {
        resolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
            ?: error("Could not open file for writing.")
    }

    /** Read the document at [uri] as UTF-8 text. */
    fun read(uri: Uri): String =
        resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: error("Could not read file.")

    /** Stream [source] into the document at [uri], for attachments too big to hold. */
    fun write(uri: Uri, source: File) {
        resolver.openOutputStream(uri, "wt")?.use { output ->
            source.inputStream().use { it.copyTo(output) }
        } ?: error("Could not open file for writing.")
    }

    /** Remove a document the picker created for a write that then failed. */
    fun discard(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }

    /**
     * Create one JSON document per ([fileName], json) entry inside the tree
     * picked as [treeUri]. Entries whose document can't be created are skipped.
     */
    fun backup(treeUri: Uri, files: List<Pair<String, String>>) {
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        for ((fileName, json) in files) {
            val fileUri = DocumentsContract.createDocument(
                resolver, dirUri, "application/json", fileName,
            ) ?: continue
            resolver.openOutputStream(fileUri)?.use { it.write(json.toByteArray()) }
        }
    }
}
