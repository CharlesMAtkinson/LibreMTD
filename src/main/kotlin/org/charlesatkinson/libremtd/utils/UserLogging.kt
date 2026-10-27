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

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.sift.Discriminator
import ch.qos.logback.core.spi.ContextAwareBase

/**
 * Which log file the process is currently writing to: <user name>.log while a
 * user is logged in, app.log before login and after logout. Two LibreMTD
 * windows (necessarily for different users) therefore never share a log file.
 *
 * Process-wide rather than MDC, because MDC is per thread and LibreMTD logs
 * from the JavaFX thread and from coroutine worker threads alike. That is
 * correct here because a process has only one logged-in user at a time.
 */
object UserLogging {
    /** The log file name (without ".log") used when no user is logged in. */
    const val DEFAULT_LOG_NAME = "app"

    private const val MAX_LENGTH = 64

    @Volatile
    private var currentLogName: String? = null

    /**
     * The log file name (without ".log") for [username]. A user name can
     * contain anything, so every character other than ASCII letters, digits,
     * full stops, hyphens and underscores becomes an underscore, and leading
     * full stops are dropped. A user name can therefore never be a path or a
     * hidden file. Two different user names can map to the same log file
     * name; the only effect is that their lines share a file.
     *
     * "app" is reserved for the pre-login log, so a user called "app" gets
     * "app_".
     */
    fun logNameFor(username: String): String {
        val cleaned = username.trim()
            .map { c ->
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '-') c else '_'
            }
            .joinToString("")
            .trimStart('.')
            .take(MAX_LENGTH)

        return when {
            cleaned.isEmpty()                                -> "user"
            cleaned.equals(DEFAULT_LOG_NAME, ignoreCase = true) -> "${DEFAULT_LOG_NAME}_"
            else                                             -> cleaned
        }
    }

    fun setCurrentUser(username: String) {
        currentLogName = logNameFor(username)
    }

    fun clearCurrentUser() {
        currentLogName = null
    }

    fun currentLogNameOrNull(): String? = currentLogName
}

/**
 * Logback discriminator for the SiftingAppender in logback.xml. It returns
 * the current user's log file name, or [defaultValue] when nobody is logged
 * in. Logback creates it by name from logback.xml and sets [key] and
 * [defaultValue] through the two setters.
 */
class CurrentUserLogDiscriminator : ContextAwareBase(), Discriminator<ILoggingEvent> {
    private var keyName = "logName"
    private var defaultValue = UserLogging.DEFAULT_LOG_NAME
    private var started = false

    override fun getDiscriminatingValue(event: ILoggingEvent): String =
        UserLogging.currentLogNameOrNull() ?: defaultValue

    override fun getKey(): String = keyName

    @Suppress("unused")   // called by Logback's configurator
    fun setKey(key: String) {
        keyName = key
    }

    @Suppress("unused")   // called by Logback's configurator
    fun setDefaultValue(value: String) {
        defaultValue = value
    }

    override fun start() {
        started = true
    }

    override fun stop() {
        started = false
    }

    override fun isStarted(): Boolean = started
}
