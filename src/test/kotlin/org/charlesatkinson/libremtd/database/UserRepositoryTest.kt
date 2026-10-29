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

package org.charlesatkinson.libremtd.database

import org.charlesatkinson.libremtd.database.tables.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * Passwords are hashed with BCrypt at 12 rounds, so each test which creates
 * or authenticates a user takes a fraction of a second. That is intended.
 */
class UserRepositoryTest {

    private lateinit var db: Database
    private lateinit var dbFile: Path

    @BeforeEach
    fun setUp() {
        // Real temp-file SQLite, not in-memory: see SettingsRepositoryTest.
        dbFile = Files.createTempFile("libremtd-test-", ".sqlite")
        db = Database.connect("jdbc:sqlite:${dbFile}", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(Users)
        }
    }

    @AfterEach
    fun tearDown() {
        transaction(db) {
            SchemaUtils.drop(Users)
        }
        dbFile.deleteIfExists()
    }

    @Test
    fun `createUser returns the new user with no HMRC tokens`() {
        val user = UserRepository.createUser("alice", "Password1")

        assertNotNull(user)
        assertEquals("alice", user!!.username)
        assertNull(user.hmrcAccessToken)
        assertNull(user.hmrcRefreshToken)
        assertNull(user.tokenExpiry)
    }

    @Test
    fun `createUser stores a hash, not the password`() {
        UserRepository.createUser("alice", "Password1")

        val stored = transaction(db) {
            Users.selectAll().single()[Users.passwordHash]
        }

        assertTrue(stored != "Password1")
        assertEquals(60, stored.length)
    }

    @Test
    fun `createUser returns null for a user name which is already taken`() {
        UserRepository.createUser("alice", "Password1")

        val second = UserRepository.createUser("alice", "Different2")

        assertNull(second)
        assertEquals(1, transaction(db) { Users.selectAll().count() }.toInt())
    }

    @Test
    fun `authenticate succeeds with the right password`() {
        val created = UserRepository.createUser("alice", "Password1")!!

        val user = UserRepository.authenticate("alice", "Password1")

        assertNotNull(user)
        assertEquals(created.id, user!!.id)
    }

    @Test
    fun `authenticate fails with the wrong password`() {
        UserRepository.createUser("alice", "Password1")

        assertNull(UserRepository.authenticate("alice", "Wrong-password1"))
    }

    @Test
    fun `authenticate fails for an unknown user`() {
        assertNull(UserRepository.authenticate("nobody", "Password1"))
    }
}
