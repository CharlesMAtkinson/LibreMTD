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

package org.charlesatkinson.libremtd.network

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResponseStatusNullOr504Test {

    private lateinit var rootLogger: Logger
    private lateinit var listAppender: ListAppender<ILoggingEvent>

    // Attached to the ROOT logger rather than a specific named logger,
    // since kotlin-logging's exact runtime logger-name resolution for a
    // singleton object isn't something I want this test to depend on —
    // WARN-level events reach root regardless of the precise name.
    @BeforeEach
    fun setUp() {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME)
        listAppender = ListAppender()
        listAppender.start()
        rootLogger.addAppender(listAppender)
    }

    @AfterEach
    fun tearDown() {
        rootLogger.detachAppender(listAppender)
    }

    @Test
    fun `logs a warning when there is no response`() {
        ResponseStatusNullOr504.record("POST", "/some/path", "{}", null)

        assertEquals(1, listAppender.list.size)
        val event = listAppender.list[0]
        assertEquals(Level.WARN, event.level)
        assertTrue(event.formattedMessage.contains("no response"))
        assertTrue(event.formattedMessage.contains("/some/path"))
    }

    @Test
    fun `logs a warning on HTTP 504, including the request body`() {
        ResponseStatusNullOr504.record("PUT", "/other/path", "{\"x\":1}", 504)

        assertEquals(1, listAppender.list.size)
        val event = listAppender.list[0]
        assertEquals(Level.WARN, event.level)
        assertTrue(event.formattedMessage.contains("504"))
        assertTrue(event.formattedMessage.contains("Request body"))
    }

    @Test
    fun `does not log for an ordinary success status`() {
        ResponseStatusNullOr504.record("GET", "/fine/path", null, 200)

        assertTrue(listAppender.list.isEmpty())
    }
}
