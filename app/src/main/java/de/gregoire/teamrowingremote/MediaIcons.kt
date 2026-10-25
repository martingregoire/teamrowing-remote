package de.gregoire.teamrowingremote

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Media icons from Material Icons (Apache License 2.0), which are only in the large extended icon
 * library. Text symbols like ⏸ aren't an alternative: some phones render them as colored emoji.
 */
object MediaIcons {

    val Pause: ImageVector by lazy {
        materialIcon(name = "Filled.Pause") {
            materialPath {
                moveTo(6f, 19f)
                horizontalLineToRelative(4f)
                verticalLineTo(5f)
                horizontalLineTo(6f)
                verticalLineToRelative(14f)
                close()
                moveTo(14f, 5f)
                verticalLineToRelative(14f)
                horizontalLineToRelative(4f)
                verticalLineTo(5f)
                horizontalLineToRelative(-4f)
                close()
            }
        }
    }

    val SkipNext: ImageVector by lazy {
        materialIcon(name = "Filled.SkipNext") {
            materialPath {
                moveTo(6f, 18f)
                lineToRelative(8.5f, -6f)
                lineTo(6f, 6f)
                verticalLineToRelative(12f)
                close()
                moveTo(16f, 6f)
                verticalLineToRelative(12f)
                horizontalLineToRelative(2f)
                verticalLineTo(6f)
                horizontalLineToRelative(-2f)
                close()
            }
        }
    }

    val SkipPrevious: ImageVector by lazy {
        materialIcon(name = "Filled.SkipPrevious") {
            materialPath {
                moveTo(6f, 6f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(12f)
                horizontalLineTo(6f)
                close()
                moveTo(9.5f, 12f)
                lineToRelative(8.5f, 6f)
                verticalLineTo(6f)
                close()
            }
        }
    }
}
