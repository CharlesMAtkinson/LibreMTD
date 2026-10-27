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
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

/**
 * Keeps each LibreMTD user to one running window, using an operating system
 * file lock on one lock file per user.
 *
 * WHY A FILE LOCK. The operating system drops the lock when the holding
 * process dies for any reason (normal exit, kill -9, a crash, a power cut),
 * so a lock can never be stale. There is nothing to clean up and no PID or
 * timestamp to judge. The lock file itself is deliberately never deleted
 * (deleting it while another process is opening it would create a race), and
 * an unlocked file with old text in it is harmless.
 *
 * The text in the file (process id and start time) is only a courtesy so a
 * refused second instance can say who holds the lock. No decision is ever
 * based on it.
 *
 * TWO JAVA/LINUX HAZARDS, both handled here:
 *
 *  1. If the FileChannel were garbage collected, the lock would be released
 *     silently. The UserInstanceLock object holds a strong reference to the
 *     channel and the FileLock for as long as the lock is held.
 *
 *  2. On Linux, closing ANY channel on the lock file within this JVM releases
 *     the operating system lock, even one taken through a different channel.
 *     So while this JVM holds a lock, the file must never be opened again by
 *     this JVM, not even to read the holder's details. [heldInThisJvm] turns
 *     a same-JVM second attempt away before the file is touched.
 *
 * Deliberately does no logging and does not use AppPaths, so it can be run by
 * itself in a test child process and the directory is always a parameter.
 *
 * Not reliable on NFS.
 */
class UserInstanceLock private constructor(
    val lockFile: Path,
    private val channel: FileChannel,
    private val fileLock: FileLock,
) : AutoCloseable {

    sealed interface Outcome {
        /** The lock is now held; call [UserInstanceLock.close] to release it. */
        class Acquired(val lock: UserInstanceLock) : Outcome

        /** Another process (or this one) holds it. [holder] is a short human-readable description. */
        class HeldElsewhere(val holder: String) : Outcome

        /** The lock could not be taken for a reason other than being held. */
        class Failed(val message: String) : Outcome
    }

    private var closed = false

    /** Releases the lock. Safe to call more than once. */
    override fun close() {
        synchronized(heldInThisJvm) {
            if (closed) return
            closed = true
            try { fileLock.release() } catch (_: IOException) { }
            try { channel.close() } catch (_: IOException) { }
            heldInThisJvm.remove(lockFile)
        }
    }

    companion object {
        /** Lock files held by this JVM. Guards every acquire and close. */
        private val heldInThisJvm = mutableSetOf<Path>()

        fun lockFileFor(dir: Path, userId: Int): Path = dir.resolve("user-$userId.lock")

        /**
         * Tries to take the lock for [userId], creating [dir] and the lock
         * file if necessary. Never blocks and never throws for an I/O problem.
         */
        fun tryAcquire(dir: Path, userId: Int): Outcome = synchronized(heldInThisJvm) {
            val file = try {
                Files.createDirectories(dir)
                lockFileFor(dir, userId).toAbsolutePath().normalize()
            } catch (e: IOException) {
                return@synchronized Outcome.Failed(
                    "Cannot create the lock directory $dir: ${e.message}"
                )
            }

            // Hazard 2 above: never open the file again while this JVM holds it.
            if (file in heldInThisJvm) {
                return@synchronized Outcome.HeldElsewhere(
                    "process ${ProcessHandle.current().pid()}, this LibreMTD"
                )
            }

            openAndLock(file)
        }

        private fun openAndLock(file: Path): Outcome {
            val channel = try {
                createLockFileIfAbsent(file)
                FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)
            } catch (e: IOException) {
                return Outcome.Failed("Cannot open the lock file $file: ${e.message}")
            }

            val fileLock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            } catch (e: IOException) {
                closeQuietly(channel)
                return Outcome.Failed(
                    "Cannot lock $file: ${e.message}.  Is the data directory on a network file system?"
                )
            }

            if (fileLock == null) {
                // This process holds nothing on the file, so closing our
                // channel cannot release anyone's lock (see hazard 2).
                closeQuietly(channel)
                return Outcome.HeldElsewhere(readHolder(file))
            }

            // Courtesy text only. Written through the channel which holds the
            // lock, never through a second one.
            try {
                val text = "pid=${ProcessHandle.current().pid()}\nstarted=${Instant.now()}\n"
                channel.truncate(0)
                channel.write(ByteBuffer.wrap(text.toByteArray(StandardCharsets.UTF_8)), 0)
            } catch (e: IOException) {
                // Not worth refusing a login for.
            }

            heldInThisJvm.add(file)
            return Outcome.Acquired(UserInstanceLock(file, channel, fileLock))
        }

        private fun createLockFileIfAbsent(file: Path) {
            try {
                if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                    Files.createFile(
                        file,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                    )
                } else {
                    Files.createFile(file)
                }
            } catch (_: FileAlreadyExistsException) {
                // Normal: the file is left in place between runs.
            }
        }

        private fun closeQuietly(channel: FileChannel) {
            try { channel.close() } catch (_: IOException) { }
        }

        /** Describes the holder from the courtesy text. Only called when this process holds no lock on [file]. */
        private fun readHolder(file: Path): String =
            try {
                val text    = Files.readString(file)
                val pid     = Regex("""pid=(\d+)""").find(text)?.groupValues?.get(1)
                val started = Regex("""started=(\S+)""").find(text)?.groupValues?.get(1)
                when {
                    pid != null && started != null -> "process $pid, started $started"
                    pid != null                    -> "process $pid"
                    else                           -> "an unknown process"
                }
            } catch (e: IOException) {
                "an unknown process"
            }
    }
}

/**
 * The lock for the user currently logged in to this process. A process has
 * one logged-in user at a time, so a single holder is enough, and it lets
 * LoginScreen take the lock and LibreMTD.stop() release it without passing
 * the lock object between them.
 */
object SessionInstanceLock {
    private var held: UserInstanceLock? = null

    /** Releases any lock already held by this session, then tries to take [userId]'s. */
    @Synchronized
    fun acquire(dir: Path, userId: Int): UserInstanceLock.Outcome {
        release()
        val outcome = UserInstanceLock.tryAcquire(dir, userId)
        if (outcome is UserInstanceLock.Outcome.Acquired) held = outcome.lock
        return outcome
    }

    @Synchronized
    fun release() {
        held?.close()
        held = null
    }
}
