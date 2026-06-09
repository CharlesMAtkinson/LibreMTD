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

object AppPaths {

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

    // Log directory (XDG_STATE_HOME/<APP_NAME>/log)
    // Must succeed or terminate
    val logDir: Path = requireDir(
        Paths.get(
            System.getenv("XDG_STATE_HOME")
                ?: "${System.getProperty("user.home")}/.local/state/$APP_NAME"
        ).resolve("log"),
        "log directory"
    )

    // Config directory (XDG_CONFIG_HOME/<APP_NAME>)
    val configPath: Path = requireDir(
        Paths.get(
            System.getenv("XDG_CONFIG_HOME")
                ?: "${System.getProperty("user.home")}/.config/$APP_NAME"
        ),
        "config directory"
    )

    // Data directory (XDG_DATA_HOME/<APP_NAME>)
    val dataDir: Path = requireDir(
        Paths.get(
            System.getenv("XDG_DATA_HOME")
                ?: "${System.getProperty("user.home")}/.local/share/$APP_NAME"
        ),
        "data directory"
    )

    // SQLite database file (inside dataDir)
    val dbPath: Path = dataDir.resolve("app.db")

    // Device ID file (/var/tmp/<APP_NAME>-deviceId)
    val deviceIdFile: Path = Paths.get("/var/tmp")
        .resolve("$APP_NAME-deviceId")
        .also { path ->
            if (!Files.exists(path.parent)) {
                logger.error {
                    "Expected /var/tmp directory does not exist; deviceIdFile may not be usable"
                }
            }
        }
}
