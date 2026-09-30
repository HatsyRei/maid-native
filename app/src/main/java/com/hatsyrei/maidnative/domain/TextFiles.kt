package com.hatsyrei.maidnative.domain

/** Which picked files count as text: by the provider's MIME type, or failing that by extension. */
object TextFiles {

    private val TEXT_MIME = setOf(
        "application/json", "application/ld+json", "application/x-ndjson", "application/xml",
        "application/yaml", "application/x-yaml", "application/toml", "application/javascript",
        "application/x-javascript", "application/ecmascript", "application/x-sh", "application/sql",
        "application/x-subrip", "application/x-tex",
    )

    /**
     * What the pickers offer. SAF filters by MIME only, and providers type .log,
     * .ini and the like as octet-stream, so that is offered too and [isText] decides.
     */
    val PICKER_TYPES: Array<String> = arrayOf("text/*", "application/octet-stream") + TEXT_MIME

    private val EXTENSIONS = setOf(
        // Notes and documents
        "txt", "text", "md", "markdown", "rst", "adoc", "org", "tex", "log", "srt", "vtt",
        // Data and config
        "csv", "tsv", "json", "jsonl", "ndjson", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf",
        "config", "properties", "env", "prefs", "reg", "plist",
        // Code
        "html", "htm", "css", "js", "mjs", "ts", "jsx", "tsx", "kt", "kts", "java", "gradle", "py",
        "rb", "go", "rs", "c", "h", "cpp", "hpp", "cc", "cs", "swift", "dart", "php", "pl", "lua",
        "r", "sql", "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "diff", "patch",
    )

    fun isText(name: String, mime: String?): Boolean {
        val type = mime?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && (type.startsWith("text/") || type in TEXT_MIME ||
                type.endsWith("+json") || type.endsWith("+xml"))
        ) {
            return true
        }
        val dot = name.lastIndexOf('.')
        return dot >= 0 && name.substring(dot + 1).lowercase() in EXTENSIONS
    }
}
