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

import org.charlesatkinson.libremtd.database.tables.HmrcSettings as HmrcSettingsTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

class SettingsRepositoryTest {

    private lateinit var db: Database
    private lateinit var dbFile: Path
    private val repository = SettingsRepository()

    @BeforeEach
    fun setUp() {
        // A real (temporary) file-based SQLite database, rather than an
        // in-memory one. SQLite's in-memory databases only persist while at
        // least one connection is open, and Exposed opens/closes a fresh
        // JDBC connection per transaction{} block — so an in-memory database
        // gets silently wiped between the schema-creation transaction and
        // the first save/load transaction. A temp file avoids that entirely
        // and behaves like the real database.
        dbFile = Files.createTempFile("libremtd-test-", ".sqlite")
        db = Database.connect("jdbc:sqlite:${dbFile}", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(HmrcSettingsTable)
        }
    }

    @AfterEach
    fun tearDown() {
        transaction(db) {
            SchemaUtils.drop(HmrcSettingsTable)
        }
        dbFile.deleteIfExists()
    }

    private fun sampleSettings(userId: Int = 1) = HmrcSettings(
        userId            = userId,
        clientId          = "client-abc",
        clientSecret      = "secret-xyz",
        nino              = "QQ123456C",
        utr               = "1234567890",
        businessIdUk      = "XBIS12345678901",
        businessIdForeign = "XBIS98765432109",
        fullName          = "Jane Taxpayer",
        dateOfBirth       = "1980-01-01",
        addressLine1      = "1 High Street",
        addressLine2      = "",
        addressLine3      = "Anytown",
        postcode          = "AB1 2CD",
    )

    @Test
    fun `save then load returns the same settings, including both business IDs`() {
        val original = sampleSettings()

        repository.save(original)
        val loaded = repository.load(original.userId)

        assertEquals(original, loaded)
    }

    @Test
    fun `businessIdUk and businessIdForeign are stored independently and not confused with each other`() {
        val original = sampleSettings().copy(
            businessIdUk      = "XBIS-UK-VALUE",
            businessIdForeign = "XBIS-FOREIGN-VALUE",
        )

        repository.save(original)
        val loaded = repository.load(original.userId)

        assertEquals("XBIS-UK-VALUE", loaded?.businessIdUk)
        assertEquals("XBIS-FOREIGN-VALUE", loaded?.businessIdForeign)
    }

    @Test
    fun `save overwrites a previous row for the same user, upsert semantics`() {
        val first = sampleSettings().copy(businessIdUk = "OLD-UK-ID")
        repository.save(first)

        val updated = first.copy(businessIdUk = "NEW-UK-ID")
        repository.save(updated)

        val loaded = repository.load(first.userId)
        assertEquals("NEW-UK-ID", loaded?.businessIdUk)
    }

    @Test
    fun `load returns null when no settings exist for that user`() {
        val loaded = repository.load(999)

        assertNull(loaded)
    }

    @Test
    fun `clear removes settings for the given user only`() {
        repository.save(sampleSettings(userId = 1))
        repository.save(sampleSettings(userId = 2))

        repository.clear(1)

        assertNull(repository.load(1))
        assertEquals(2, repository.load(2)?.userId)
    }
}