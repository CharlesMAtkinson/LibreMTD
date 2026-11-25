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
class ConnectPaneStateTest {

    // ── controls ──────────────────────────────────────────────────────────

    @Test
    fun `disconnected and idle shows an enabled Connect button only`() {
        val s = connectPaneState(connected = false, authorising = false)

        assertTrue(s.showConnect)
        assertTrue(s.connectEnabled)
        assertFalse(s.showDisconnect)
        assertFalse(s.showAuthorisingNotice)
    }

    @Test
    fun `while a window is open Connect is shown but disabled and the notice is shown`() {
        val s = connectPaneState(connected = false, authorising = true)

        assertTrue(s.showConnect)
        assertFalse(s.connectEnabled)
        assertFalse(s.showDisconnect)
        assertTrue(s.showAuthorisingNotice)
    }

    @Test
    fun `connected and idle shows an enabled Disconnect button only`() {
        val s = connectPaneState(connected = true, authorising = false)

        assertFalse(s.showConnect)
        assertTrue(s.showDisconnect)
        assertTrue(s.disconnectEnabled)
        assertFalse(s.showAuthorisingNotice)
    }

    @Test
    fun `connected while a window is open disables Disconnect too`() {
        val s = connectPaneState(connected = true, authorising = true)

        assertTrue(s.showDisconnect)
        assertFalse(s.disconnectEnabled)
        assertTrue(s.showAuthorisingNotice)
    }

    @Test
    fun `Connect and Disconnect are never shown together`() {
        for (connected in listOf(true, false)) {
            for (authorising in listOf(true, false)) {
                val s = connectPaneState(connected, authorising)

                assertTrue(s.showConnect != s.showDisconnect, "connected=$connected authorising=$authorising")
            }
        }
    }

    @Test
    fun `nothing can be pressed while a window is open`() {
        for (connected in listOf(true, false)) {
            val s = connectPaneState(connected, authorising = true)

            assertFalse(s.connectEnabled)
            assertFalse(s.disconnectEnabled)
        }
    }

    // ── status bar ────────────────────────────────────────────────────────

    @Test
    fun `a pane built while a window is open shows Authenticating, not Disconnected`() {
        assertEquals(MtdConnectionStatus.Authenticating, connectionStatusFor(connected = false, authorising = true))
    }

    @Test
    fun `the other combinations give Connected or Disconnected`() {
        assertEquals(MtdConnectionStatus.Disconnected, connectionStatusFor(connected = false, authorising = false))
        assertEquals(MtdConnectionStatus.Connected, connectionStatusFor(connected = true, authorising = false))
    }

    @Test
    fun `a usable token wins over an open window`() {
        assertEquals(MtdConnectionStatus.Connected, connectionStatusFor(connected = true, authorising = true))
    }

    // ── wording ───────────────────────────────────────────────────────────

    @Test
    fun `the notice says the window is already open and tells the user what to do`() {
        assertTrue(AUTHORISATION_OPEN_HINT.contains("already open"))
        assertTrue(AUTHORISATION_OPEN_HINT.contains("Complete it or close it"))
    }

    @Test
    fun `the notice uses no em-dashes`() {
        assertFalse(AUTHORISATION_OPEN_HINT.contains('\u2014'))
    }
}
