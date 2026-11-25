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

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Only the HelpPane.Topic enum is touched, so no JavaFX toolkit is needed:
 * a nested enum can be used without initialising the pane around it.
 */
class HelpTopicTest {

    @Test
    fun `no help topic or navigation destination is about allowances`() {
        assertTrue(HelpPane.Topic.entries.none { it.name.contains("Allowance", ignoreCase = true) })
        assertTrue(HelpPane.Topic.entries.none { it.label.contains("Allowance", ignoreCase = true) })
        assertTrue(NavDestination.entries.none { it.name.contains("Allowance", ignoreCase = true) })
    }

    @Test
    fun `a saved help topic name which no longer exists matches no topic`() {
        // HelpPane looks the saved name up this way and falls back to
        // Introduction, so a user whose last topic was Allowances is safe.
        assertTrue(HelpPane.Topic.entries.none { it.name == "Allowances" })
    }
}
