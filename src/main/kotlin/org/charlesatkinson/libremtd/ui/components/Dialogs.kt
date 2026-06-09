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

import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.image.Image
import javafx.scene.layout.Region
import javafx.stage.Stage

import mu.KotlinLogging
private val logger = KotlinLogging.logger {}

/**
 * Application-wide dialog helpers.
 *
 * showError() presents a copyable Label inside an ERROR Alert. The user can
 * context-click the message text and choose Copy to copy it to the clipboard —
 * useful for pasting API error bodies into a support request or log. This
 * feature is documented in the Help.
 *
 * All UI panes should use these instead of inline Alert construction so that the
 * look and behaviour are consistent throughout the application.
 */
object Dialogs {

    private val appIcons: List<Image> by lazy {
        listOf(256, 48, 16).mapNotNull { size ->
            val stream = Dialogs::class.java.getResourceAsStream("/icons/libremtd-$size.png")
            stream?.let { Image(it) }
        }
    }

    private fun Alert.applyAppIcons() {
        setOnShowing {
            val window = dialogPane.scene?.window
            val stage  = window as? Stage
            if (stage != null) {
                stage.icons.setAll(appIcons)
            } else {
                logger.warn { "applyAppIcons: stage is null, cannot set icons" }
            }
        }
    }

    /**
     * Shows a modal ERROR alert whose content is a selectable, copyable Label.
     * Blocks until the user dismisses it.
     *
     * @param message  The error text to display.
     * @param title    Optional window title (defaults to "Error").
     */
    fun showError(message: String, title: String = "Error") {
        val label = wrappingLabel(message).apply {
            maxWidth = 480.0
        }

        Alert(Alert.AlertType.ERROR).apply {
            this.title           = title
            headerText           = null
            dialogPane.content   = label
            dialogPane.minHeight = Region.USE_PREF_SIZE
            applyAppIcons()
            showAndWait()
        }
    }

    /**
     * Shows a modal CONFIRMATION alert and returns true if the user clicked OK.
     * Blocks until the user dismisses it.
     *
     * @param message     The question or warning text.
     * @param title       Window title.
     * @param headerText  Optional bold header line above the message.
     */
    fun showConfirmation(
        message:    String,
        title:      String,
        headerText: String? = null,
    ): Boolean {
        val result = Alert(Alert.AlertType.CONFIRMATION).apply {
            this.title      = title
            this.headerText = headerText
            contentText     = message
            applyAppIcons()
        }.showAndWait()

        return result.isPresent && result.get() == ButtonType.OK
    }
}