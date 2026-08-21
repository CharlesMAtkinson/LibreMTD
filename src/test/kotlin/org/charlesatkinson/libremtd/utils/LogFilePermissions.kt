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

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals

class LogFilePermissionsTest {

    @Test
    fun `fixes permissions on the active log file and rolled-over log files`(@TempDir tempDir: Path) {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))

        val activeLog = tempDir.resolve("app.log")
        val rolledLog = tempDir.resolve("app-2026-01-08.log")
        Files.createFile(activeLog)
        Files.createFile(rolledLog)

        // Simulate the world-readable permissions currently seen in the wild.
        Files.setPosixFilePermissions(activeLog, PosixFilePermissions.fromString("rw-r--r--"))
        Files.setPosixFilePermissions(rolledLog, PosixFilePermissions.fromString("rw-rw-r--"))

        enforceLogFilePermissions(tempDir)

        val expected = PosixFilePermissions.fromString("rw-rw----")
        assertEquals(expected, Files.getPosixFilePermissions(activeLog))
        assertEquals(expected, Files.getPosixFilePermissions(rolledLog))
    }

    @Test
    fun `leaves unrelated files in the log directory untouched`(@TempDir tempDir: Path) {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))

        val otherFile = tempDir.resolve("notes.txt")
        Files.createFile(otherFile)
        val original = PosixFilePermissions.fromString("rw-r--r--")
        Files.setPosixFilePermissions(otherFile, original)

        enforceLogFilePermissions(tempDir)

        assertEquals(original, Files.getPosixFilePermissions(otherFile))
    }

    @Test
    fun `does nothing if the directory does not exist`() {
        // Should not throw.
        enforceLogFilePermissions(Path.of("/nonexistent/path/for/test"))
    }
}
