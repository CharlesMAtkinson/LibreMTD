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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

/**
 * Tests xdgAppDir, xdgWebViewDir, restrictToOwner and createPrivateDir only.
 * The AppPaths object itself creates directories (and exits the process if it
 * cannot), so it is deliberately not touched here.
 */
class AppPathsTest {

    private val ownerOnly = PosixFilePermissions.fromString("rwx------")

    private fun assumePosix() =
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))

    // ── xdgAppDir ─────────────────────────────────────────────────────────

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

    // ── xdgWebViewDir ─────────────────────────────────────────────────────

    @Test
    fun `an unset state variable puts the WebView directory under the default state directory`() {
        assertEquals(
            Paths.get("/home/jo/.local/state/LibreMTD/webview/alice"),
            xdgWebViewDir(null, "/home/jo", "alice"),
        )
    }

    @Test
    fun `an absolute state variable is used for the WebView directory`() {
        assertEquals(
            Paths.get("/data/xdg-state/LibreMTD/webview/alice"),
            xdgWebViewDir("/data/xdg-state", "/home/jo", "alice"),
        )
    }

    @Test
    fun `an empty, blank or relative state variable is ignored for the WebView directory`() {
        val expected = Paths.get("/home/jo/.local/state/LibreMTD/webview/alice")

        assertEquals(expected, xdgWebViewDir("", "/home/jo", "alice"))
        assertEquals(expected, xdgWebViewDir("   ", "/home/jo", "alice"))
        assertEquals(expected, xdgWebViewDir("relative/dir", "/home/jo", "alice"))
    }

    @Test
    fun `each user has a different WebView directory beside the others`() {
        val alice = xdgWebViewDir(null, "/home/jo", "alice")
        val bob   = xdgWebViewDir(null, "/home/jo", "bob")

        assertNotEquals(alice, bob)
        assertEquals(alice.parent, bob.parent)
    }

    @Test
    fun `the WebView directory name is the same as the user's log file name`() {
        for (name in listOf("alice", "bob smith", "a.b_c-d9", "app", "App", "..", "../etc/passwd")) {
            assertEquals(
                UserLogging.logNameFor(name),
                xdgWebViewDir(null, "/home/jo", name).fileName.toString(),
                "user name: \"$name\"",
            )
        }
    }

    @Test
    fun `characters which are not safe in a directory name become underscores`() {
        assertEquals(
            Paths.get("/home/jo/.local/state/LibreMTD/webview/my_name"),
            xdgWebViewDir(null, "/home/jo", "my name"),
        )
    }

    @Test
    fun `a path traversal attempt cannot escape the webview directory`() {
        val webView = xdgAppDir(null, "/home/jo", ".local/state").resolve("webview")

        val dir = xdgWebViewDir(null, "/home/jo", "../etc/passwd")

        assertEquals(webView.resolve("_etc_passwd"), dir)
        assertEquals(webView, dir.parent)
    }

    @Test
    fun `a name with nothing usable gets a safe directory name`() {
        assertEquals("user", xdgWebViewDir(null, "/home/jo", "..").fileName.toString())
        assertEquals("user", xdgWebViewDir(null, "/home/jo", "   ").fileName.toString())
    }

    @Test
    fun `passing an already reduced log name gives the same directory`() {
        val name = "bob smith"

        assertEquals(
            xdgWebViewDir(null, "/home/jo", name),
            xdgWebViewDir(null, "/home/jo", UserLogging.logNameFor(name)),
        )
    }

    @Test
    fun `the WebView directory is under the same application directory as the log directory`() {
        val appDir = xdgAppDir("/data/xdg-state", "/home/jo", ".local/state")

        assertTrue(xdgWebViewDir("/data/xdg-state", "/home/jo", "alice").startsWith(appDir))
    }

    // ── permissions ───────────────────────────────────────────────────────

    @Test
    fun `createPrivateDir creates missing parents and restricts only the last directory to its owner`(@TempDir tempDir: Path) {
        assumePosix()
        val parent = tempDir.resolve("a")
        val leaf   = parent.resolve("b")

        val result = createPrivateDir(leaf)

        assertEquals(leaf, result)
        assertTrue(Files.isDirectory(leaf))
        assertEquals(ownerOnly, Files.getPosixFilePermissions(leaf))
        // The parent was made with the default permissions: it is not this function's to restrict.
        assertTrue(Files.isDirectory(parent))
    }

    @Test
    fun `createPrivateDir tightens an existing directory which was made with wider permissions`(@TempDir tempDir: Path) {
        assumePosix()
        val dir = tempDir.resolve("existing")
        Files.createDirectory(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))

        createPrivateDir(dir)

        assertEquals(ownerOnly, Files.getPosixFilePermissions(dir))
    }

    @Test
    fun `createPrivateDir leaves the contents of an existing directory alone`(@TempDir tempDir: Path) {
        assumePosix()
        val dir  = tempDir.resolve("existing")
        val file = dir.resolve("app.db")
        Files.createDirectory(dir)
        Files.writeString(file, "data")

        createPrivateDir(dir)

        assertEquals("data", Files.readString(file))
    }

    @Test
    fun `restrictToOwner on a directory which does not exist does not throw`(@TempDir tempDir: Path) {
        assumePosix()

        restrictToOwner(tempDir.resolve("missing"))
    }

    @Test
    fun `a per-user WebView directory path can be created and is private`(@TempDir tempDir: Path) {
        assumePosix()
        val dir = xdgWebViewDir(tempDir.toString(), "/unused", "bob")

        createPrivateDir(dir.parent)
        createPrivateDir(dir)

        assertEquals(ownerOnly, Files.getPosixFilePermissions(dir.parent))
        assertEquals(ownerOnly, Files.getPosixFilePermissions(dir))
        assertEquals("bob", dir.fileName.toString())
    }
}
