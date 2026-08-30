/*
 *
 *  * Copyright (C) 2026 Charles Michael Atkinson
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package org.charlesatkinson.libremtd.ui

import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.stage.Stage
import mu.KotlinLogging
import org.charlesatkinson.libremtd.ui.components.ThemeManager
import org.charlesatkinson.libremtd.ui.components.UiPreferences

private val logger = KotlinLogging.logger {}

/**
 * Hosts a single, reusable, modeless Help window.
 *
 * Help is deliberately not shown inside MainWindow's contentArea: HelpPane is
 * a compound widget (its own sidebar plus its own internal ScrollPane) and
 * repeated attempts to host it within MainWindow's height-constrained
 * contentArea produced a spurious vertical scrollbar that could not be
 * resolved. A dedicated Stage sidesteps that entirely, and — as a bonus —
 * lets the user keep Help open whilst working on other panes.
 *
 * Only one Help window exists per running application. Calling [show] again
 * while it is already open brings it to the front and retargets it to the
 * requested topic, rather than opening a second window.
 */
object HelpWindow {
    private var stage: Stage? = null
    private var pane: HelpPane? = null

    private const val DEFAULT_WIDTH  = 800.0
    private const val DEFAULT_HEIGHT = 600.0

    fun show(userId: Int, topic: HelpPane.Topic = HelpPane.Topic.Introduction) {
        val existingStage = stage
        val existingPane = pane

        if (existingStage != null && existingStage.isShowing && existingPane != null) {
            existingPane.selectTopic(topic)
            existingStage.toFront()
            existingStage.requestFocus()
            return
        }

        val prefs = UiPreferences(userId)
        val helpPane = HelpPane(userId = userId, initialTopic = topic)
        val scene = Scene(helpPane, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        ThemeManager.apply(scene, prefs)

        val newStage = Stage().apply {
            title = "LibreMTD Help"
            this.scene = scene
        }

        loadIcon()?.let { newStage.icons.add(it) }

        // Restore saved geometry, falling back to the Scene's default size above.
        prefs.helpWindowWidth?.let  { newStage.width  = it }
        prefs.helpWindowHeight?.let { newStage.height = it }
        prefs.helpWindowX?.let      { newStage.x      = it }
        prefs.helpWindowY?.let      { newStage.y      = it }

        // Persist geometry whenever the window is moved or resized.
        newStage.xProperty().addListener      { _, _, v -> prefs.helpWindowX      = v.toDouble() }
        newStage.yProperty().addListener      { _, _, v -> prefs.helpWindowY      = v.toDouble() }
        newStage.widthProperty().addListener  { _, _, v -> prefs.helpWindowWidth  = v.toDouble() }
        newStage.heightProperty().addListener { _, _, v -> prefs.helpWindowHeight = v.toDouble() }

        newStage.setOnCloseRequest {
            logger.info { "Help window closed" }
        }

        stage = newStage
        pane = helpPane

        newStage.show()
    }

    private fun loadIcon(): Image? =
        try {
            javaClass.getResourceAsStream("/icons/libremtd-128.png")?.use { Image(it) }
        } catch (e: Exception) {
            logger.warn { "Could not load Help window icon: ${e.message}" }
            null
        }
}