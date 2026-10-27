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

import org.charlesatkinson.libremtd.utils.UserInstanceLock.Outcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

class UserInstanceLockTest {

    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun releaseSession() {
        SessionInstanceLock.release()
    }

    private fun acquired(outcome: Outcome): UserInstanceLock {
        assertTrue(outcome is Outcome.Acquired, "Expected Acquired but got ${describe(outcome)}")
        return (outcome as Outcome.Acquired).lock
    }

    private fun describe(outcome: Outcome) = when (outcome) {
        is Outcome.Acquired       -> "Acquired"
        is Outcome.HeldElsewhere  -> "HeldElsewhere(${outcome.holder})"
        is Outcome.Failed         -> "Failed(${outcome.message})"
    }

    // ── in one JVM ────────────────────────────────────────────────────────

    @Test
    fun `a first acquire succeeds and a second for the same user is refused`() {
        val first = acquired(UserInstanceLock.tryAcquire(tempDir, 1))

        val second = UserInstanceLock.tryAcquire(tempDir, 1)

        assertTrue(second is Outcome.HeldElsewhere, describe(second))
        first.close()
    }

    @Test
    fun `different users can be held at the same time`() {
        val one = acquired(UserInstanceLock.tryAcquire(tempDir, 1))
        val two = acquired(UserInstanceLock.tryAcquire(tempDir, 2))

        one.close()
        two.close()
    }

    @Test
    fun `after close the same user can be acquired again`() {
        acquired(UserInstanceLock.tryAcquire(tempDir, 1)).close()

        acquired(UserInstanceLock.tryAcquire(tempDir, 1)).close()
    }

    @Test
    fun `closing twice is harmless`() {
        val lock = acquired(UserInstanceLock.tryAcquire(tempDir, 1))

        lock.close()
        lock.close()

        acquired(UserInstanceLock.tryAcquire(tempDir, 1)).close()
    }

    @Test
    fun `the lock file records this process id and a start time`() {
        val lock = acquired(UserInstanceLock.tryAcquire(tempDir, 7))

        val text = Files.readString(UserInstanceLock.lockFileFor(tempDir, 7))

        assertTrue(text.contains("pid=${ProcessHandle.current().pid()}"), text)
        assertTrue(text.contains("started="), text)
        lock.close()
    }

    @Test
    fun `a leftover lock file with garbage in it and no holder is acquired normally`() {
        // Simulates the file left behind by a crash: content is stale and nobody holds the lock.
        Files.writeString(UserInstanceLock.lockFileFor(tempDir, 1), "pid=999999\nstarted=long ago\n\u0000garbage")

        val lock = acquired(UserInstanceLock.tryAcquire(tempDir, 1))

        val text = Files.readString(UserInstanceLock.lockFileFor(tempDir, 1))
        assertTrue(text.contains("pid=${ProcessHandle.current().pid()}"), text)
        assertTrue(!text.contains("garbage"), text)
        lock.close()
    }

    @Test
    fun `an unusable lock directory gives Failed rather than an exception`() {
        val blocker = tempDir.resolve("blocker")
        Files.writeString(blocker, "I am a file, not a directory")

        val outcome = UserInstanceLock.tryAcquire(blocker.resolve("locks"), 1)

        assertTrue(outcome is Outcome.Failed, describe(outcome))
    }

    @Test
    fun `the session holder releases the previous lock before acquiring`() {
        val first = SessionInstanceLock.acquire(tempDir, 1)
        assertTrue(first is Outcome.Acquired, describe(first))

        SessionInstanceLock.release()

        // If release had not really released, this would be refused.
        acquired(UserInstanceLock.tryAcquire(tempDir, 1)).close()
    }

    // ── across processes ──────────────────────────────────────────────────

    private val javaBinary: Path = Paths.get(System.getProperty("java.home"), "bin", "java")

    /** Classpath for the child JVM: main classes, test classes and the Kotlin standard library only. */
    private fun childClasspath(): String =
        listOf(
            UserInstanceLock::class.java,
            Class.forName("org.charlesatkinson.libremtd.utils.UserInstanceLockHolderKt"),
            Unit::class.java,
        ).map { Paths.get(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct()
            .joinToString(File.pathSeparator)

    private fun startChild(mode: String, userId: Int): Process =
        ProcessBuilder(
            javaBinary.toString(), "-cp", childClasspath(),
            "org.charlesatkinson.libremtd.utils.UserInstanceLockHolderKt",
            mode, tempDir.toString(), userId.toString(),
        ).redirectErrorStream(true).start()

    private fun firstLine(process: Process): String? =
        assertTimeoutPreemptively(
            Duration.ofSeconds(30),
            ThrowingSupplier { process.inputStream.bufferedReader().readLine() },
        )

    private fun runChildTry(userId: Int): String? {
        val child = startChild("try", userId)
        try {
            return firstLine(child)
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    fun `a lock held by another process is refused, and is free again once that process is killed`() {
        assumeTrue(Files.isExecutable(javaBinary))
        val child = startChild("hold", 1)
        try {
            assertEquals("LOCKED", firstLine(child))

            val refused = UserInstanceLock.tryAcquire(tempDir, 1)
            assertTrue(refused is Outcome.HeldElsewhere, describe(refused))
            assertTrue(
                (refused as Outcome.HeldElsewhere).holder.contains(child.pid().toString()),
                "The message should name the holder's process id: ${refused.holder}",
            )

            // The crash: no chance to clean up.
            child.destroyForcibly()
            child.waitFor()

            acquired(UserInstanceLock.tryAcquire(tempDir, 1)).close()
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    fun `a refused second attempt in this process does not drop the lock held here`() {
        assumeTrue(Files.isExecutable(javaBinary))
        val mine = acquired(UserInstanceLock.tryAcquire(tempDir, 1))
        try {
            assertEquals("HELD", runChildTry(1))

            // On Linux, opening and closing another channel on the lock file would
            // release the operating system lock. This must not have done so.
            assertTrue(UserInstanceLock.tryAcquire(tempDir, 1) is Outcome.HeldElsewhere)

            assertEquals("HELD", runChildTry(1))
        } finally {
            mine.close()
        }
        assertEquals("ACQUIRED", runChildTry(1))
    }

    @Test
    fun `another process can hold a different user at the same time`() {
        assumeTrue(Files.isExecutable(javaBinary))
        val mine = acquired(UserInstanceLock.tryAcquire(tempDir, 1))
        try {
            assertEquals("ACQUIRED", runChildTry(2))
        } finally {
            mine.close()
        }
    }
}
