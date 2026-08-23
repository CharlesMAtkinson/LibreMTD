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
import javafx.scene.control.Label
import javafx.scene.input.Clipboard
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start

/**
 * Runs against a real JavaFX display (the ApplicationExtension starts the
 * JavaFX toolkit and opens an actual Stage) — no headless configuration is
 * in place, per the project's current agreement. If test runs ever need to
 * happen somewhere without a display (CI, SSH), the plan is to wrap the
 * Gradle test run with Xvfb rather than change anything in this class.
 */
@ExtendWith(ApplicationExtension::class)
class ReadOnlyLabelsTest {

    private lateinit var stage: Stage

    // TestFX invokes this via reflection because of the @Start annotation —
    // it's never called directly from our own code, so IntelliJ's "unused
    // function" inspection can't see the real caller. @Suppress silences
    // that false positive.
    @Suppress("unused")
    @Start
    private fun start(stage: Stage) {
        this.stage = stage
        stage.show()
    }

    // ── hintLabel(): property checks ───────────────────────────────────

    @Test
    fun `hintLabel applies the hint-label style class`() {
        val label = hintLabel("Some hint text")
        assertTrue(label.styleClass.contains("hint-label"))
    }

    @Test
    fun `hintLabel wraps its text`() {
        val label = hintLabel("Some hint text")
        assertTrue(label.isWrapText)
    }

    @Test
    fun `hintLabel is not clamped to a single-line preferred width`() {
        val label = hintLabel("Some hint text")
        assertEquals(Double.MAX_VALUE, label.maxWidth)
        assertEquals(0.0, label.minWidth)
    }

    @Test
    fun `hintLabel does not shrink below its wrapped preferred height`() {
        // This is the clipping-bug fix documented in ReadOnlyLabels.kt: a
        // VBox parent can otherwise size a wrapped label down to its
        // single-line construction-time height, cutting off later lines.
        val label = hintLabel("Some hint text")
        assertEquals(Region.USE_PREF_SIZE, label.minHeight)
    }

    // ── wrappingLabel(): contrast check ────────────────────────────────

    @Test
    fun `wrappingLabel wraps text but does not add hint-label styling`() {
        val label = wrappingLabel("Some plain wrapping text")
        assertTrue(label.isWrapText)
        assertFalse(label.styleClass.contains("hint-label"))
    }

    // ── Copy context menu: TestFX interaction check ────────────────────

    @Test
    fun `right-clicking a copyable label and choosing Copy puts its text on the clipboard`(robot: FxRobot) {
        val text = "Text to copy via the right-click menu"
        lateinit var label: Label

        robot.interact {
            label = wrappingLabel(text)
            stage.scene = Scene(VBox(label), 300.0, 100.0)
        }

        robot.rightClickOn(label)
        robot.clickOn("Copy")

        var clipboardText: String? = null
        robot.interact {
            clipboardText = Clipboard.getSystemClipboard().string
        }

        assertEquals(text, clipboardText)
    }
}