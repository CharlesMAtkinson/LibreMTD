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

import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Timer
import kotlin.concurrent.timer

/**
 * The permission every LibreMTD log file should have: readable and
 * writable by the owner and group, and completely inaccessible to
 * everyone else. Log files can contain sensitive data such as NINOs
 * and UTRs, so this is deliberately tighter than the world-readable
 * permissions some existing log files currently have.
 */
private val LOG_FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-rw----")

/**
 * Every file in the log directory which LibreMTD's logging can create:
 * app.log, <user name>.log and their daily rolled-over files such as
 * app-2026-01-08.log and alice-2026-01-08.log. User names are reduced to
 * letters, digits, full stops, hyphens and underscores in log file names
 * (see UserLogging.logNameFor), so this pattern covers them all.
 */
private val LOG_FILE_NAME_PATTERN = Regex("""[A-Za-z0-9._-]+\.log""")

/**
 * Scans [logDir] for files matching the log naming pattern
 * (app.log, alice.log, app-2026-01-08.log, etc.) and, on POSIX
 * filesystems, sets their permissions to rw-rw---- if they are not
 * already correct.
 *
 * This exists because Logback does not manage file permissions itself:
 * files are created with whatever mode the JVM process's umask
 * dictates at the time it creates them, which is why existing log
 * files have inconsistent permissions. Calling this after logging
 * starts, after each login (which creates that user's log file), and
 * periodically thereafter, keeps permissions consistent regardless of
 * the umask in effect when the process was launched or when a file is
 * rolled over.
 *
 * Does nothing on non-POSIX filesystems (e.g. Windows). Silently
 * skips any file it cannot change, so a permissions problem never
 * stops the application from logging.
 */
fun enforceLogFilePermissions(logDir: Path) {
    if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
        return
    }
    if (!Files.isDirectory(logDir)) return

    Files.newDirectoryStream(logDir).use { stream ->
        for (path in stream) {
            if (!Files.isRegularFile(path)) continue
            if (!LOG_FILE_NAME_PATTERN.matches(path.fileName.toString())) continue

            try {
                if (Files.getPosixFilePermissions(path) != LOG_FILE_PERMISSIONS) {
                    Files.setPosixFilePermissions(path, LOG_FILE_PERMISSIONS)
                }
            } catch (e: IOException) {
                // Best-effort: one unreadable/unchangeable file should
                // not stop the application from starting or logging.
            }
        }
    }
}

/**
 * Starts a background task that re-applies [enforceLogFilePermissions]
 * every [intervalMinutes] minutes, to catch the daily rollover file if
 * the application is left running across midnight.
 */
fun scheduleLogFilePermissionEnforcement(logDir: Path, intervalMinutes: Long = 30): Timer =
    kotlin.concurrent.timer(
        name = "log-permission-enforcer",
        daemon = true,
        initialDelay = intervalMinutes * 60_000,
        period = intervalMinutes * 60_000
    ) {
        enforceLogFilePermissions(logDir)
    }
