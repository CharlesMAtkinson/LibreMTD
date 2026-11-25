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

package org.charlesatkinson.libremtd.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests the pure gate only. AuthorisationWindow needs the JavaFX toolkit
 * (and a real window), so it is checked by hand.
 */
class AuthorisationGateTest {

    @Test
    fun `a new gate is not held`() {
        assertFalse(AuthorisationGate().isHeld())
    }

    @Test
    fun `the first caller gets in and a second is refused while it is held`() {
        val gate = AuthorisationGate()

        assertTrue(gate.tryEnter())
        assertTrue(gate.isHeld())
        assertFalse(gate.tryEnter())
        assertTrue(gate.isHeld())
    }

    @Test
    fun `after leave the gate can be entered again`() {
        val gate = AuthorisationGate()
        gate.tryEnter()

        gate.leave()

        assertFalse(gate.isHeld())
        assertTrue(gate.tryEnter())
    }

    @Test
    fun `leaving a gate which is not held is harmless`() {
        val gate = AuthorisationGate()

        gate.leave()

        assertFalse(gate.isHeld())
        assertTrue(gate.tryEnter())
    }

    @Test
    fun `leaving twice is harmless and does not let two callers in`() {
        val gate = AuthorisationGate()
        gate.tryEnter()

        gate.leave()
        gate.leave()

        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())
    }

    @Test
    fun `only one of many simultaneous callers gets in`() {
        val gate    = AuthorisationGate()
        val callers = 16
        val start   = CountDownLatch(1)
        val winners = AtomicInteger()
        val pool    = Executors.newFixedThreadPool(callers)

        try {
            val futures = (1..callers).map {
                pool.submit(Runnable {
                    start.await()
                    if (gate.tryEnter()) winners.incrementAndGet()
                })
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, winners.get())
        assertTrue(gate.isHeld())
    }
}
