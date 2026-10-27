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

package org.charlesatkinson.libremtd.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Tests xdgAppDir only. The AppPaths object itself creates directories (and
 * exits the process if it cannot), so it is deliberately not touched here.
 */
class AppPathsTest {

    @Test
    fun `an unset variable falls back to the default under the home directory`() {
        assertEquals(
            Paths.get("/home/jo/.local/state/LibreMTD"),
            xdgAppDir(null, "/home/jo", ".local/state"),
        )
    }

    @Test
    fun `an absolute variable is used with the application name added`() {
        assertEquals(
            Paths.get("/data/xdg-state/LibreMTD"),
            xdgAppDir("/data/xdg-state", "/home/jo", ".local/state"),
        )
    }

    @Test
    fun `an empty or blank variable is treated as unset`() {
        assertEquals(Paths.get("/home/jo/.local/share/LibreMTD"), xdgAppDir("", "/home/jo", ".local/share"))
        assertEquals(Paths.get("/home/jo/.local/share/LibreMTD"), xdgAppDir("   ", "/home/jo", ".local/share"))
    }

    @Test
    fun `a relative variable is ignored, as the XDG specification requires`() {
        assertEquals(
            Paths.get("/home/jo/.local/share/LibreMTD"),
            xdgAppDir("relative/dir", "/home/jo", ".local/share"),
        )
    }
}
