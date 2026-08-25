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

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class TokenStoreTest {

    @BeforeEach
    fun clearBefore() {
        TokenStore.reset()
    }

    @AfterEach
    fun clearAfter() {
        TokenStore.reset()
    }

    @Test
    fun `isConnected is false when no token has ever been stored`() {
        assertFalse(TokenStore.isConnected())
    }

    @Test
    fun `isConnected is true for a token expiring well in the future`() {
        TokenStore.restore(
            accessToken  = "access-123",
            refreshToken = "refresh-123",
            expiresAt    = LocalDateTime.now().plusHours(1),
        )

        assertTrue(TokenStore.isConnected())
    }

    @Test
    fun `isConnected is false for a token that has already expired`() {
        TokenStore.restore(
            accessToken  = "access-123",
            refreshToken = "refresh-123",
            expiresAt    = LocalDateTime.now().minusMinutes(5),
        )

        assertFalse(TokenStore.isConnected())
    }

    @Test
    fun `isConnected is false inside the one-minute early-expiry window`() {
        // isExpired() treats anything within the last minute before expiresAt
        // as already expired, so a token expiring in 30 seconds must count
        // as not connected.
        TokenStore.restore(
            accessToken  = "access-123",
            refreshToken = "refresh-123",
            expiresAt    = LocalDateTime.now().plusSeconds(30),
        )

        assertFalse(TokenStore.isConnected())
    }

    @Test
    fun `isConnected is false after clearMemory even if expiry would still be valid`() {
        TokenStore.restore(
            accessToken  = "access-123",
            refreshToken = "refresh-123",
            expiresAt    = LocalDateTime.now().plusHours(1),
        )
        TokenStore.clearMemory()

        assertFalse(TokenStore.isConnected())
    }
}