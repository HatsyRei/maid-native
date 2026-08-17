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
