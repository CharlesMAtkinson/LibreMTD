/*
 * Copyright (C) 2026 Charles Michael Atkinson
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.charlesatkinson.libremtd.ui.components

import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.Labeled
import javafx.scene.control.MenuItem
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Popup

/**
 * Factory functions for Labels that carry a "Copy" context menu.
 *
 * copyableText()  — wrapping label suited to longer messages such as error
 *                   bodies. The user can context-click and choose Copy to copy
 *                   the full text to the clipboard. This is the documented way
 *                   to copy any label text in the application.
 *
 * Both functions are thin wrappers around makeCopyableLabel() so that the
 * context-menu behaviour lives in exactly one place.
 */

fun wrappingLabel(text: String): Label = makeCopyableLabel(text).apply {
    isWrapText = true
}

fun hintLabel(text: String): Label = wrappingLabel(text).apply {
    styleClass.add("hint-label")
    maxWidth = Double.MAX_VALUE
    minWidth = 0.0
    // Without this, a VBox parent can size the label down to the single-line
    // height it defaults to at construction time, clipping any wrapped
    // second (or later) line even though maxWidth/minWidth above are letting
    // it wrap correctly. USE_PREF_SIZE tells the layout to never shrink
    // below the label's own computed (post-wrap) preferred height.
    minHeight = Region.USE_PREF_SIZE
}

/**
 * Creates a small clickable info icon that shows a copyable popup when clicked.
 * The popup dismisses when focus is lost or the user presses Escape.
 * Preferred over hover tooltips for UI clarity.
 */
fun infoPopup(text: String): Label {
    val icon = Label("ⓘ").apply {
        style = "-fx-cursor: hand; -fx-text-fill: -fx-accent; -fx-font-size: 13px;"
    }

    val contentLabel = makeCopyableLabel(text).apply {
        isWrapText  = true
        maxWidth    = 320.0
        style       = "-fx-font-size: 13px; -fx-padding: 8 10 8 10;"
    }

    val popup = Popup().apply {
        isAutoHide     = true
        isAutoFix      = true
        content.add(
            VBox(contentLabel).apply {
                styleClass.add("tooltip")          // reuse JavaFX's .tooltip CSS class
                style = "-fx-background-radius: 4; -fx-border-radius: 4; -fx-padding: 4;"
            }
        )
    }

    icon.setOnMouseClicked { event ->
        if (popup.isShowing) {
            popup.hide()
        } else {
            popup.show(icon, event.screenX + 8, event.screenY + 8)
        }
        event.consume()
    }

    return icon
}

/**
 * Attaches the standard "right-click > Copy" context menu to any Labeled
 * node (Label, TableCell, etc.), copying whatever that node's text is at
 * the moment Copy is clicked — not a fixed value captured up front, so it
 * works correctly on TableCells whose text changes as rows are recycled.
 * makeCopyableLabel() uses this internally; call it directly for nodes
 * that aren't a fresh Label, so the context-menu behaviour still lives in
 * exactly one place.
 */
fun attachCopyContextMenu(target: Labeled) {
    val copyItem = MenuItem("Copy").apply {
        style = "-fx-font-size: 13px; -fx-font-weight: normal;"
        setOnAction {
            val clipboard = Clipboard.getSystemClipboard()
            val content   = ClipboardContent()
            content.putString(target.text)
            clipboard.setContent(content)
        }
    }
    val contextMenu = ContextMenu(copyItem)
    target.setOnContextMenuRequested { event ->
        contextMenu.show(target, event.screenX, event.screenY)
    }
}

private fun makeCopyableLabel(initialText: String): Label {
    val label = Label(initialText)
    attachCopyContextMenu(label)
    return label
}