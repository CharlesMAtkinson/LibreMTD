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

package org.charlesatkinson.libremtd.utils

import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

const val APP_NAME = "LibreMTD"

/**
 * The application's directory under an XDG base directory:
 * "<envValue>/LibreMTD" when [envValue] is set to an absolute path,
 * otherwise "<home>/<defaultRelative>/LibreMTD". As the XDG specification
 * requires, an empty or relative value is treated as unset.
 *
 * A top-level function, separate from the AppPaths object, so tests can call
 * it without AppPaths creating any directories.
 */
internal fun xdgAppDir(envValue: String?, home: String, defaultRelative: String): Path {
    val base = envValue
        ?.takeIf { it.isNotBlank() && Paths.get(it).isAbsolute }
        ?.let { Paths.get(it) }
        ?: Paths.get(home, defaultRelative)
    return base.resolve(APP_NAME)
}

object AppPaths {

    private val home: String = System.getProperty("user.home")

    // Helper: require a directory to exist or terminate
    private fun requireDir(path: Path, description: String): Path {
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path)
            }
            return path
        } catch (e: Exception) {
            System.err.println("Fatal: cannot create $description at $path: ${e.message}")
            exitProcess(1)
        }
    }

    // A file in /var/tmp, shared by every Linux user of the computer
    private fun varTmpFile(name: String): Path =
        Paths.get("/var/tmp")
            .resolve(name)
            .also { path ->
                if (!Files.exists(path.parent)) {
                    logger.error {
                        "Expected /var/tmp directory does not exist; $name may not be usable"
                    }
                }
            }

    // Log directory ($XDG_STATE_HOME/<APP_NAME>/log)
    // Must succeed or terminate.  logback.xml works out the same directory.
    val logDir: Path = requireDir(
        xdgAppDir(System.getenv("XDG_STATE_HOME"), home, ".local/state").resolve("log"),
        "log directory"
    )

    // Data directory ($XDG_DATA_HOME/<APP_NAME>)
    val dataDir: Path = requireDir(
        xdgAppDir(System.getenv("XDG_DATA_HOME"), home, ".local/share"),
        "data directory"
    )

    // SQLite database file (inside dataDir)
    val dbPath: Path = dataDir.resolve("app.db")

    // One lock file per LibreMTD user (inside dataDir, so it follows the
    // database). See UserInstanceLock.
    val locksDir: Path = requireDir(dataDir.resolve("locks"), "lock directory")

    // Device ID file (/var/tmp/<APP_NAME>-deviceId)
    val deviceIdFile: Path = varTmpFile("$APP_NAME-deviceId")

    // Licence ID file (/var/tmp/<APP_NAME>-licenceId)
    val licenceIdFile: Path = varTmpFile("$APP_NAME-licenceId")
}
