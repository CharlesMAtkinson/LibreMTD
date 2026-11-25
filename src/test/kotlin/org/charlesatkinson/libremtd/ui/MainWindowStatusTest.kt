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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure functions only: no JavaFX toolkit and no database.
 */
class MainWindowStatusTest {

    @Test
    fun `the Connect pane visited during an authorisation says authenticating`() {
        assertEquals(
            AUTHENTICATING_STATUS_TEXT,
            navigationStatusText(NavDestination.HmrcConnect, authorising = true),
        )
    }

    @Test
    fun `the Connect pane visited with no authorisation says Viewing`() {
        assertEquals(
            "Viewing: Connect",
            navigationStatusText(NavDestination.HmrcConnect, authorising = false),
        )
    }

    @Test
    fun `every other pane says Viewing even during an authorisation`() {
        for (dest in NavDestination.entries.filter { it != NavDestination.HmrcConnect }) {
            assertEquals(
                "Viewing: ${dest.label}",
                navigationStatusText(dest, authorising = true),
                dest.name,
            )
        }
    }

    @Test
    fun `the authenticating text does not claim the window is still opening`() {
        assertFalse(AUTHENTICATING_STATUS_TEXT.contains("Opening"))
        assertTrue(AUTHENTICATING_STATUS_TEXT.startsWith("Authenticating"))
    }

    @Test
    fun `the authenticating text uses no em-dashes`() {
        assertFalse(AUTHENTICATING_STATUS_TEXT.contains('\u2014'))
    }
}
