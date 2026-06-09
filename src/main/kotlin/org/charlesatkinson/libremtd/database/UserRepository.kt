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

package org.charlesatkinson.libremtd.database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.charlesatkinson.libremtd.database.tables.Users
import org.charlesatkinson.libremtd.security.PasswordManager
import java.time.LocalDateTime

data class User(
    val id: Int,
    val username: String,
    val email: String?,
    val hmrcAccessToken: String?,
    val hmrcRefreshToken: String?,
    val tokenExpiry: LocalDateTime?
)

object UserRepository {
    
    fun createUser(username: String, password: String, nino: String, email: String? = null): User? {
        return transaction {
            val passwordHash = PasswordManager.hashPassword(password)
            val now = LocalDateTime.now()
            
            val userId = Users.insert {
                it[Users.username] = username
                it[Users.passwordHash] = passwordHash
                it[Users.email] = email
                it[createdAt] = now
            } get Users.id
            
            User(
                id = userId,
                username = username,
                email = email,
                hmrcAccessToken = null,
                hmrcRefreshToken = null,
                tokenExpiry = null
            )
        }
    }
    
    fun authenticate(username: String, password: String): User? {
        return transaction {
            val row = Users.selectAll()
                .where { Users.username eq username }
                .singleOrNull() ?: return@transaction null

            val hash = row[Users.passwordHash]
            if (!PasswordManager.verifyPassword(password, hash)) {
                return@transaction null
            }

            // Update last login
            Users.update({ Users.username eq username }) {
                it[lastLogin] = LocalDateTime.now()
            }

            return@transaction User(
                id = row[Users.id],
                username = row[Users.username],
                email = row[Users.email],
                hmrcAccessToken = row[Users.hmrcAccessToken],
                hmrcRefreshToken = row[Users.hmrcRefreshToken],
                tokenExpiry = row[Users.tokenExpiry]
            )
        }
    }
    
    fun updateHMRCTokens(
        userId: Int,
        accessToken: String,
        refreshToken: String,
        expiresIn: Int
    ) {
        transaction {
            Users.update({ Users.id eq userId }) {
                it[hmrcAccessToken] = accessToken
                it[hmrcRefreshToken] = refreshToken
                it[tokenExpiry] = LocalDateTime.now().plusSeconds(expiresIn.toLong())
            }
        }
    }

    fun clearHMRCTokens(userId: Int) {
        transaction {
            Users.update({ Users.id eq userId }) {
                it[hmrcAccessToken]  = null
                it[hmrcRefreshToken] = null
                it[tokenExpiry]      = null
            }
        }
    }
}
