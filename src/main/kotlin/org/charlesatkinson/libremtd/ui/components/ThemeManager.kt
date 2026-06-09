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

import javafx.scene.Scene
import mu.KotlinLogging
import org.charlesatkinson.libremtd.ui.components.UiPreferences
import org.charlesatkinson.libremtd.ui.components.UiTheme

private val logger = KotlinLogging.logger {}

/**
 * Applies and switches the UI theme on a JavaFX [Scene].
 *
 * application.css  — layout, spacing, fonts, no colours
 * green-theme.css  — all colour rules for the green theme.  Used on the login screen
 * light-theme.css  — all colour rules for the light theme
 * dark-theme.css   — all colour rules for the dark theme
 *
 * Usage:
 *   ThemeManager.apply(scene)           // on startup
 *   ThemeManager.switchTo(scene, theme) // when the user picks a new theme
 */
object ThemeManager {

    private fun cssUrl(name: String): String =
        ThemeManager::class.java.getResource("/styles/$name")?.toExternalForm()
            ?: error("Theme CSS not found: /styles/$name")

    fun apply(scene: Scene, prefs: UiPreferences) {
        val saved = prefs.theme
        logger.info { "ThemeManager.apply: saved theme is ${saved.name}" }
        switchTo(scene, saved, prefs)
    }

    fun switchTo(scene: Scene, theme: UiTheme, prefs: UiPreferences) {
        logger.info { "ThemeManager.switchTo: switching to ${theme.name}" }
        logger.info { "ThemeManager.switchTo: stylesheets before = ${scene.stylesheets}" }
        prefs.theme = theme

        scene.stylesheets.removeIf {
            it.contains("dark-theme") ||
                    it.contains("light-theme") ||
                    it.contains("green-theme")
        }

        val themeFile = when (theme) {
            UiTheme.DARK  -> "dark-theme.css"
            UiTheme.LIGHT -> "light-theme.css"
            UiTheme.GREEN -> "green-theme.css"
        }

        scene.stylesheets.add(cssUrl(themeFile))
        logger.info { "ThemeManager.switchTo: stylesheets after = ${scene.stylesheets}" }
    }
}