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
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
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

/**
 * The directory in which the HMRC sign-in WebView keeps its local storage
 * for the LibreMTD user called [userName]: "<state dir>/LibreMTD/webview/<name>",
 * where the state directory is [envValue] (XDG_STATE_HOME) if that is an
 * absolute path, otherwise "<home>/.local/state".
 *
 * The directory name is [userName] reduced by [UserLogging.logNameFor], the
 * same reduction used for log file names, so it is always a safe single path
 * element (a user name can never be a path or a hidden directory) and it
 * matches the user's log file name. An already reduced name is unchanged by
 * the reduction, so the current log name can be passed in directly. Two
 * different user names can reduce to the same directory name, exactly as for
 * log files; the only effect is that they share a directory.
 *
 * Per user, so that whatever HMRC's sign-in pages remember is not shared
 * between LibreMTD users.
 *
 * A top-level function, like [xdgAppDir], so tests can call it without any
 * directory being created.
 */
internal fun xdgWebViewDir(envValue: String?, home: String, userName: String): Path =
    xdgAppDir(envValue, home, ".local/state")
        .resolve("webview")
        .resolve(UserLogging.logNameFor(userName))

private val OWNER_ONLY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")

/**
 * Makes [path] readable, writable and searchable by its owner only (mode
 * 0700), as the XDG specification asks of directories created on demand.
 * Applies to an existing directory as well, so a directory created by an
 * earlier version of LibreMTD with the default permissions is corrected.
 *
 * Does nothing on non-POSIX filesystems (such as Windows). Best-effort: a
 * failure is logged and does not stop the application.
 */
internal fun restrictToOwner(path: Path) {
    if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return
    try {
        Files.setPosixFilePermissions(path, OWNER_ONLY_PERMISSIONS)
    } catch (e: IOException) {
        logger.warn { "Could not restrict $path to its owner: ${e.message}" }
    }
}

/**
 * Creates [path], and any missing parents, then restricts [path] itself to
 * its owner (see [restrictToOwner]). Missing parents get the default
 * permissions. Returns [path].
 */
internal fun createPrivateDir(path: Path): Path {
    Files.createDirectories(path)
    restrictToOwner(path)
    return path
}

object AppPaths {

    private val home: String = System.getProperty("user.home")

    // Helper: require a directory to exist or terminate. With [ownerOnly] the
    // directory is also restricted to its owner each time, which corrects a
    // directory made earlier with the default permissions.
    private fun requireDir(path: Path, description: String, ownerOnly: Boolean = false): Path {
        try {
            Files.createDirectories(path)
            if (ownerOnly) restrictToOwner(path)
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
    // Owner only: log files can contain sensitive data such as NINOs and UTRs
    // (see LogFilePermissions.kt, which makes the files owner only as well).
    val logDir: Path = requireDir(
        xdgAppDir(System.getenv("XDG_STATE_HOME"), home, ".local/state").resolve("log"),
        "log directory",
        ownerOnly = true,
    )

    // Data directory ($XDG_DATA_HOME/<APP_NAME>). Holds the database, which
    // has the HMRC client secret and OAuth tokens in it, so owner only.
    val dataDir: Path = requireDir(
        xdgAppDir(System.getenv("XDG_DATA_HOME"), home, ".local/share"),
        "data directory",
        ownerOnly = true,
    )

    // SQLite database file (inside dataDir)
    val dbPath: Path = dataDir.resolve("app.db")

    // One lock file per LibreMTD user (inside dataDir, so it follows the
    // database). See UserInstanceLock. Deliberately not in $XDG_RUNTIME_DIR,
    // which the system may empty when the user logs out of the computer, and
    // which is not beside the database the lock protects.
    val locksDir: Path = requireDir(dataDir.resolve("locks"), "lock directory", ownerOnly = true)

    // Device ID file (/var/tmp/<APP_NAME>-deviceId)
    val deviceIdFile: Path = varTmpFile("$APP_NAME-deviceId")

    // Licence ID file (/var/tmp/<APP_NAME>-licenceId)
    val licenceIdFile: Path = varTmpFile("$APP_NAME-licenceId")

    /**
     * The directory for the HMRC sign-in WebView's local storage for the
     * LibreMTD user called [userName]
     * ($XDG_STATE_HOME/<APP_NAME>/webview/<user name, as in log file names>),
     * created if necessary and restricted to its owner, as is the webview
     * directory above it. Throws [IOException] if it cannot be created:
     * unlike the other directories this is not needed to start the
     * application, so the caller decides what to do.
     */
    fun webViewDataDir(userName: String): Path {
        val dir = xdgWebViewDir(System.getenv("XDG_STATE_HOME"), home, userName)
        createPrivateDir(dir.parent)
        return createPrivateDir(dir)
    }
}
