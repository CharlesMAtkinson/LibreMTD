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

import ch.qos.logback.classic.spi.LoggingEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UserLoggingTest {

    @AfterEach
    fun reset() {
        UserLogging.clearCurrentUser()
    }

    // ── logNameFor ────────────────────────────────────────────────────────

    @Test
    fun `a plain user name is used unchanged`() {
        assertEquals("alice", UserLogging.logNameFor("alice"))
        assertEquals("a.b_c-d9", UserLogging.logNameFor("a.b_c-d9"))
    }

    @Test
    fun `characters which are not safe in a file name become underscores`() {
        assertEquals("my_name", UserLogging.logNameFor("my name"))
        assertEquals("_lodie", UserLogging.logNameFor("Élodie".drop(0).replaceFirst("É", "É")).take(6))
    }

    @Test
    fun `a path traversal attempt cannot escape the log directory`() {
        val name = UserLogging.logNameFor("../etc/passwd")

        assertFalse(name.contains("/"))
        assertFalse(name.startsWith("."))
        assertEquals("_etc_passwd", name)
    }

    @Test
    fun `a name with nothing usable falls back to user`() {
        assertEquals("user", UserLogging.logNameFor(".."))
        assertEquals("user", UserLogging.logNameFor("   "))
    }

    @Test
    fun `the name of the pre-login log file is reserved`() {
        assertEquals("app_", UserLogging.logNameFor("app"))
        assertEquals("app_", UserLogging.logNameFor("App"))
    }

    @Test
    fun `a very long name is shortened`() {
        assertEquals(64, UserLogging.logNameFor("a".repeat(100)).length)
    }

    // ── current user and the discriminator ────────────────────────────────

    @Test
    fun `with no user the discriminator returns its default value`() {
        val discriminator = CurrentUserLogDiscriminator()
        discriminator.setDefaultValue("fallback")

        assertEquals("fallback", discriminator.getDiscriminatingValue(LoggingEvent()))
    }

    @Test
    fun `with a user set the discriminator returns the sanitised user log name`() {
        val discriminator = CurrentUserLogDiscriminator()
        UserLogging.setCurrentUser("bob smith")

        assertEquals("bob_smith", discriminator.getDiscriminatingValue(LoggingEvent()))
    }

    @Test
    fun `clearing the user returns the discriminator to the default`() {
        val discriminator = CurrentUserLogDiscriminator()
        UserLogging.setCurrentUser("bob")
        UserLogging.clearCurrentUser()

        assertEquals("app", discriminator.getDiscriminatingValue(LoggingEvent()))
    }

    @Test
    fun `the key and started state behave as logback expects`() {
        val discriminator = CurrentUserLogDiscriminator()
        discriminator.setKey("logName")

        assertEquals("logName", discriminator.getKey())
        assertFalse(discriminator.isStarted())
        discriminator.start()
        assertTrue(discriminator.isStarted())
        discriminator.stop()
        assertFalse(discriminator.isStarted())
    }
}
