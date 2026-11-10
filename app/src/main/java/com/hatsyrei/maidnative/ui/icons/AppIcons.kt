package com.hatsyrei.maidnative.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * A couple of vector icons that ship in `material-icons-extended` but not in the
 * bundled `material-icons-core` set. Defined inline so we don't pull the whole
 * extended icon pack (keeps the APK small, per SPEC size goals).
 */
val ContentCopyIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ContentCopy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(2f)
            verticalLineTo(3f)
            horizontalLineToRelative(12f)
            verticalLineTo(1f)
            close()
            moveTo(19f, 5f)
            horizontalLineTo(8f)
            curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
            verticalLineToRelative(14f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(11f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(7f)
            curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
            close()
            moveTo(19f, 21f)
            horizontalLineTo(8f)
            verticalLineTo(7f)
            horizontalLineToRelative(11f)
            verticalLineTo(21f)
            close()
        }
    }.build()
}

val ArrowUpwardIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ArrowUpward",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(4f, 12f)
            lineToRelative(1.41f, 1.41f)
            lineTo(11f, 7.83f)
            verticalLineTo(20f)
            horizontalLineToRelative(2f)
            verticalLineTo(7.83f)
            lineToRelative(5.58f, 5.59f)
            lineTo(20f, 12f)
            lineToRelative(-8f, -8f)
            lineToRelative(-8f, 8f)
            close()
        }
    }.build()
}

/** Material `file_download` (per-chat Export, mirrors the RN chat menu icon). */
val FileDownloadIcon: ImageVector by lazy {
    materialIcon("FileDownload", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
}

/** Material `folder_open` (drawer Import button, mirrors the RN load-mappings icon). */
val FolderOpenIcon: ImageVector by lazy {
    materialIcon(
        "FolderOpen",
        "M20,6h-8l-2,-2H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 " +
            "2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2zM20,18L4,18V8h16v10z",
    )
}

/** Material `save_alt` (drawer Backup-all button, mirrors the RN backup icon). */
val SaveAltIcon: ImageVector by lazy {
    materialIcon(
        "SaveAlt",
        "M19,12v7H5v-7H3v7c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2zM13,12.67l2.59,-2.58" +
            "L17,11.5l-5,5 -5,-5 1.41,-1.41L11,12.67V3h2v9.67z",
    )
}

/** Material `bookmarks` (Settings endpoint-preset button). */
val BookmarksIcon: ImageVector by lazy {
    materialIcon(
        "Bookmarks",
        "M19,18l2,1V3c0,-1.1 -0.9,-2 -2,-2H8.99C7.89,1 7,1.9 7,3h10c1.1,0 2,0.9 2,2v13z" +
            "M15,5H5C3.9,5 3,5.9 3,7v16l7,-3 7,3V7c0,-1.1 -0.9,-2 -2,-2z",
    )
}

/** Material `add` (composer attachment button). */
val AddIcon: ImageVector by lazy {
    materialIcon("Add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
}

/** Material `info_outline` (chat Properties menu entry). */
val InfoOutlineIcon: ImageVector by lazy {
    materialIcon(
        "InfoOutline",
        "M11,7h2v2h-2V7zM11,11h2v6h-2v-6zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 " +
            "10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 " +
            "8,-8 8,3.59 8,8 -3.59,8 -8,8z",
    )
}

/** Material `tune` (Settings sampling button). */
val TuneIcon: ImageVector by lazy {
    materialIcon(
        "Tune",
        "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7z" +
            "M21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z",
    )
}

/** Material `repeat` (Settings penalties button). */
val RepeatIcon: ImageVector by lazy {
    materialIcon("Repeat", "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4z")
}

/** Material `close` (removes a pending attachment chip). */
val CloseIcon: ImageVector by lazy {
    materialIcon(
        "Close",
        "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
    )
}

/** Material `image` (attachment menu). */
val ImageIcon: ImageVector by lazy {
    materialIcon(
        "Image",
        "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2z" +
            "M8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z",
    )
}

/** Material `audiotrack` (attachment menu). */
val AudiotrackIcon: ImageVector by lazy {
    materialIcon(
        "Audiotrack",
        "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z",
    )
}

/** Material `description` (attachment menu). */
val DescriptionIcon: ImageVector by lazy {
    materialIcon(
        "Description",
        "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6z" +
            "M16,18H8v-2h8v2zM16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z",
    )
}

/** Material `pause` (audio playback; `play_arrow` ships in material-icons-core). */
val PauseIcon: ImageVector by lazy {
    materialIcon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")
}

/** Material `schedule` (Date & time tool). */
val ScheduleIcon: ImageVector by lazy {
    materialIcon(
        "Schedule",
        "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2z" +
            "M12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8z" +
            "M12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
    )
}

/** Material `casino` (Dice roller tool). */
val CasinoIcon: ImageVector by lazy {
    materialIcon(
        "Casino",
        "M19,3H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2z" +
            "M7.5,18c-0.83,0 -1.5,-0.67 -1.5,-1.5S6.67,15 7.5,15s1.5,0.67 1.5,1.5S8.33,18 7.5,18z" +
            "M7.5,9C6.67,9 6,8.33 6,7.5S6.67,6 7.5,6 9,6.67 9,7.5 8.33,9 7.5,9z" +
            "M12,13.5c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z" +
            "M16.5,18c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z" +
            "M16.5,9c-0.83,0 -1.5,-0.67 -1.5,-1.5S15.67,6 16.5,6s1.5,0.67 1.5,1.5S17.33,9 16.5,9z",
    )
}

/** Material `link` (Fetch URL tool). */
val LinkIcon: ImageVector by lazy {
    materialIcon(
        "Link",
        "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7" +
            "c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1" +
            "s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z",
    )
}

/** Material `code` (JavaScript tool). */
val CodeIcon: ImageVector by lazy {
    materialIcon("Code", "M9.4,16.6L4.8,12l4.6,-4.6L8,6l-6,6 6,6 1.4,-1.4zM14.6,16.6l4.6,-4.6 -4.6,-4.6L16,6l6,6 -6,6 -1.4,-1.4z")
}

/** Material `dns` (MCP servers). */
val DnsIcon: ImageVector by lazy {
    materialIcon(
        "Dns",
        "M20,13H4c-0.55,0 -1,0.45 -1,1v6c0,0.55 0.45,1 1,1h16c0.55,0 1,-0.45 1,-1v-6c0,-0.55 -0.45,-1 -1,-1z" +
            "M7,19c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z" +
            "M20,3H4c-0.55,0 -1,0.45 -1,1v6c0,0.55 0.45,1 1,1h16c0.55,0 1,-0.45 1,-1V4c0,-0.55 -0.45,-1 -1,-1z" +
            "M7,9c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z",
    )
}

/** Material `format_quote` (reply prefill). */
val FormatQuoteIcon: ImageVector by lazy {
    materialIcon("FormatQuote", "M6,17h3l2,-4V7H5v6h3zM14,17h3l2,-4V7h-6v6h3z")
}

private fun materialIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.White),
        )
    }.build()
