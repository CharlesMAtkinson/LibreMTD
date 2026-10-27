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

import java.nio.file.Paths

/**
 * Not a test: a tiny program which UserInstanceLockTest starts as a separate
 * JVM process, so that the lock can be tested across real process boundaries.
 *
 * Arguments: mode ("hold" or "try"), lock directory, user id.
 *
 *  - hold: acquire the lock, print LOCKED, then sleep until killed.
 *  - try:  try to acquire the lock, print ACQUIRED, HELD or FAILED, and exit.
 */
fun main(args: Array<String>) {
    val mode   = args[0]
    val dir    = Paths.get(args[1])
    val userId = args[2].toInt()

    when (val outcome = UserInstanceLock.tryAcquire(dir, userId)) {
        is UserInstanceLock.Outcome.Acquired -> {
            if (mode == "hold") {
                println("LOCKED")
                System.out.flush()
                Thread.sleep(Long.MAX_VALUE)
            } else {
                println("ACQUIRED")
                outcome.lock.close()
            }
        }
        is UserInstanceLock.Outcome.HeldElsewhere -> println("HELD")
        is UserInstanceLock.Outcome.Failed        -> println("FAILED ${outcome.message}")
    }
}
