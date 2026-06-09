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

package org.charlesatkinson.libremtd.security

import mu.KotlinLogging
import org.charlesatkinson.libremtd.database.UserRepository
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

// ── Token storage ─────────────────────────────────────────────────────────────

object TokenStore {
    private var accessToken:  String?        = null
    private var refreshToken: String?        = null
    private var expiresAt:    LocalDateTime? = null

    fun store(result: OAuth2Handler.OAuth2Result, userId: Int) {
        accessToken  = result.accessToken
        refreshToken = result.refreshToken
        expiresAt    = LocalDateTime.now().plusSeconds(result.expiresIn.toLong())

        UserRepository.updateHMRCTokens(
            userId       = userId,
            accessToken  = result.accessToken,
            refreshToken = result.refreshToken ?: "",
            expiresIn    = result.expiresIn,
        )
        logger.info { "Tokens stored for userId=$userId, expires at $expiresAt" }
    }

    fun getAccessToken():  String? = accessToken
    fun getRefreshToken(): String? = refreshToken

    fun isExpired(): Boolean {
        val exp = expiresAt ?: return true
        // Treat as expired 1 minute early to avoid edge cases
        return LocalDateTime.now().isAfter(exp.minusMinutes(1))
    }

    fun clear(userId: Int) {
        accessToken  = null
        refreshToken = null
        expiresAt    = null
        UserRepository.clearHMRCTokens(userId)
        logger.info { "Token store cleared from memory and database" }
    }

    fun clearMemory() {
        accessToken  = null
        refreshToken = null
        expiresAt    = null
        logger.info { "Token store cleared from memory (not from database)" }
    }

    fun restore(
        accessToken:  String,
        refreshToken: String,
        expiresAt:    LocalDateTime,
    ) {
        this.accessToken  = accessToken
        this.refreshToken = refreshToken
        this.expiresAt    = expiresAt
        logger.info { "Token store restored from database, expires at $expiresAt" }
    }
}