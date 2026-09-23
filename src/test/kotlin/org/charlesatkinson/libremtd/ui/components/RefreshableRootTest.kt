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

package org.charlesatkinson.libremtd.ui.components

import javafx.scene.control.Label
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension

/**
 * No @Start/Stage needed — RefreshableRoot only wraps a Node and calls a
 * lambda, neither of which requires a live Scene. @ExtendWith is kept
 * anyway to ensure the JavaFX toolkit is initialised, since Label() itself
 * requires it.
 */
@ExtendWith(ApplicationExtension::class)
class RefreshableRootTest {

    @Test
    fun `wraps the given content as its only child`() {
        val content = Label("content")

        val wrapper = RefreshableRoot(content) {}

        assertEquals(1, wrapper.children.size)
        assertSame(content, wrapper.children.first())
    }

    @Test
    fun `refresh invokes the callback`() {
        var called = false

        val wrapper = RefreshableRoot(Label("content")) { called = true }
        wrapper.refresh()

        assertTrue(called)
    }

    @Test
    fun `refresh can be called more than once`() {
        var count = 0

        val wrapper = RefreshableRoot(Label("content")) { count++ }
        wrapper.refresh()
        wrapper.refresh()

        assertEquals(2, count)
    }
}
