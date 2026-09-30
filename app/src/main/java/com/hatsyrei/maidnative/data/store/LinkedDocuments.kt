package com.hatsyrei.maidnative.data.store

import android.content.ContentResolver
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import android.provider.OpenableColumns
import com.hatsyrei.maidnative.domain.TextFiles
import com.hatsyrei.maidnative.domain.tools.Documents
import com.hatsyrei.maidnative.domain.tools.LinkedFile
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Linked files through the Storage Access Framework: the system picker grants
 * access to the one document chosen, and a persisted grant keeps it across
 * restarts, so no storage permission is needed. Every persisted grant this app
 * holds is a linked file.
 */
class LinkedDocuments(private val resolver: ContentResolver) : Documents {

    /**
     * Keeps access to a picked text document; write access when its provider
     * allows it. A file the user just [created] is text by intent, whatever its extension.
     */
    fun link(uri: Uri, created: Boolean): LinkedFile {
        val name = displayName(uri) ?: uri.lastPathSegment ?: "file"
        require(created || TextFiles.isText(name, resolver.getType(uri))) { "\"$name\" is not a supported text file" }
        try {
            resolver.takePersistableUriPermission(uri, READ or WRITE)
        } catch (e: SecurityException) {
            resolver.takePersistableUriPermission(uri, READ)
        }
        return LinkedFile(uri.toString(), name)
    }

    /** Gives back the grants of files no chat links any more. */
    fun releaseExcept(kept: Set<String>) {
        for (grant in resolver.persistedUriPermissions) {
            if (grant.uri.toString() in kept) continue
            runCatching { resolver.releasePersistableUriPermission(grant.uri, flags(grant)) }
        }
    }

    override fun read(uri: String, limit: Int): ByteArray {
        val target = granted(uri, write = false)
        val input = resolver.openInputStream(target) ?: throw FileNotFoundException("The file could not be opened")
        return input.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() <= limit) {
                val n = it.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }

    override fun write(uri: String, bytes: ByteArray) {
        val target = granted(uri, write = true)
        val output = resolver.openOutputStream(target, "wt") ?: throw FileNotFoundException("The file could not be opened")
        output.use { it.write(bytes) }
    }

    // Only documents the user picked: an imported chat can name any URI, file:// into app storage included.
    private fun granted(uri: String, write: Boolean): Uri {
        val parsed = Uri.parse(uri)
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == parsed }
            ?: throw IOException("This app no longer has access to the file; the user needs to link it again")
        if (write && !grant.isWritePermission) throw IOException("The file is read-only")
        return parsed
    }

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun flags(grant: UriPermission): Int =
        (if (grant.isReadPermission) READ else 0) or (if (grant.isWritePermission) WRITE else 0)

    private companion object {
        const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
        const val WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
