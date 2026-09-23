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

import org.charlesatkinson.libremtd.database.tables.ForeignPropertyElections
import org.charlesatkinson.libremtd.database.tables.Properties
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

class PropertyRepositoryTest {

    private lateinit var db: Database
    private lateinit var dbFile: Path

    @BeforeEach
    fun setUp() {
        // Real temp-file SQLite, not in-memory — see SettingsRepositoryTest
        // for why: Exposed opens a fresh JDBC connection per transaction{}
        // block, and an in-memory SQLite database is wiped as soon as its
        // one open connection closes, so schema created in one transaction
        // would vanish before the next.
        //
        // ForeignPropertyElections is created alongside Properties because
        // PropertyRepository.remove() deletes from both tables.
        dbFile = Files.createTempFile("libremtd-test-", ".sqlite")
        db = Database.connect("jdbc:sqlite:${dbFile}", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(Properties, ForeignPropertyElections)
        }
    }

    @AfterEach
    fun tearDown() {
        transaction(db) {
            SchemaUtils.drop(ForeignPropertyElections, Properties)
        }
        dbFile.deleteIfExists()
    }

    @Test
    fun `create creates a UK property with the given address and postcode`() {
        val property = PropertyRepository.create(
            userId = 1, address = "1 High Street", propertyType = PropertyType.UK,
            postcode = "AB1 2CD",
        )

        assertEquals("1 High Street", property.address)
        assertEquals("AB1 2CD", property.postcode)
        assertEquals(PropertyType.UK, property.propertyType)
    }

    @Test
    fun `create creates a foreign property with the given country code`() {
        val property = PropertyRepository.create(
            userId = 1, address = "10 Rue de Paris", propertyType = PropertyType.FOREIGN,
            countryCode = "FRA",
        )

        assertEquals("FRA", property.countryCode)
        assertNull(property.postcode)
    }

    @Test
    fun `create throws if a UK property has no postcode`() {
        assertThrows(IllegalArgumentException::class.java) {
            PropertyRepository.create(userId = 1, address = "1 High Street", propertyType = PropertyType.UK)
        }
    }

    @Test
    fun `create throws if a foreign property has no country code`() {
        assertThrows(IllegalArgumentException::class.java) {
            PropertyRepository.create(userId = 1, address = "10 Rue de Paris", propertyType = PropertyType.FOREIGN)
        }
    }

    @Test
    fun `findByUser returns only properties for that user`() {
        PropertyRepository.create(userId = 1, address = "1 High Street", propertyType = PropertyType.UK, postcode = "AB1 2CD")
        PropertyRepository.create(userId = 2, address = "2 Low Street", propertyType = PropertyType.UK, postcode = "AB2 3DE")

        val found = PropertyRepository.findByUser(1)

        assertEquals(1, found.size)
        assertEquals("1 High Street", found.single().address)
    }

    @Test
    fun `findByUser excludes removed properties`() {
        val property = PropertyRepository.create(
            userId = 1, address = "1 High Street", propertyType = PropertyType.UK, postcode = "AB1 2CD",
        )
        PropertyRepository.remove(property.id)

        val found = PropertyRepository.findByUser(1)

        assertTrue(found.isEmpty())
    }

    @Test
    fun `findById returns null for an unknown id`() {
        assertNull(PropertyRepository.findById(999))
    }

    @Test
    fun `findById returns the property with the given id`() {
        val property = PropertyRepository.create(
            userId = 1, address = "1 High Street", propertyType = PropertyType.UK, postcode = "AB1 2CD",
        )

        val found = PropertyRepository.findById(property.id)

        assertEquals(property, found)
    }

    @Test
    fun `updateUk changes the address and postcode`() {
        val property = PropertyRepository.create(
            userId = 1, address = "1 High Street", propertyType = PropertyType.UK, postcode = "AB1 2CD",
        )

        val updated = PropertyRepository.updateUk(property.id, "2 New Street", "ZZ9 9ZZ")

        assertEquals("2 New Street", updated.address)
        assertEquals("ZZ9 9ZZ", updated.postcode)

        val reloaded = PropertyRepository.findById(property.id)
        assertEquals("2 New Street", reloaded?.address)
        assertEquals("ZZ9 9ZZ", reloaded?.postcode)
    }

    @Test
    fun `updateUk does not affect other properties`() {
        val target = PropertyRepository.create(
            userId = 1, address = "1 High Street", propertyType = PropertyType.UK, postcode = "AB1 2CD",
        )
        val other = PropertyRepository.create(
            userId = 1, address = "2 Low Street", propertyType = PropertyType.UK, postcode = "AB2 3DE",
        )

        PropertyRepository.updateUk(target.id, "1a High Street", "AB1 2CE")

        val reloadedOther = PropertyRepository.findById(other.id)
        assertEquals("2 Low Street", reloadedOther?.address)
        assertEquals("AB2 3DE", reloadedOther?.postcode)
    }

    @Test
    fun `updateUk throws for a foreign property`() {
        val property = PropertyRepository.create(
            userId = 1, address = "10 Rue de Paris", propertyType = PropertyType.FOREIGN, countryCode = "FRA",
        )

        assertThrows(IllegalArgumentException::class.java) {
            PropertyRepository.updateUk(property.id, "11 Rue de Paris", "AB1 2CD")
        }
    }

    @Test
    fun `updateUk throws for an unknown property id`() {
        assertThrows(IllegalArgumentException::class.java) {
            PropertyRepository.updateUk(999, "1 High Street", "AB1 2CD")
        }
    }

    @Test
    fun `updateForeignAddress changes the address`() {
        val property = PropertyRepository.create(
            userId = 1, address = "10 Rue de Paris", propertyType = PropertyType.FOREIGN, countryCode = "FRA",
        )

        val updated = PropertyRepository.updateForeignAddress(property.id, "11 Rue de Paris")

        assertEquals("11 Rue de Paris", updated.address)

        val reloaded = PropertyRepository.findById(property.id)
        assertEquals("11 Rue de Paris", reloaded?.address)
    }

    @Test
    fun `updateForeignAddress does not change the country code`() {
        val property = PropertyRepository.create(
            userId = 1, address = "10 Rue de Paris", propertyType = PropertyType.FOREIGN, countryCode = "FRA",
        )

        val updated = PropertyRepository.updateForeignAddress(property.id, "11 Rue de Paris")

        assertEquals("FRA", updated.countryCode)
    }

    @Test
    fun `updateForeignAddress throws for a UK property`() {
        val property = PropertyRepository.create(
            userId = 1, address = "1 High Street", propertyType = PropertyType.UK, postcode = "AB1 2CD",
        )

        assertThrows(IllegalArgumentException::class.java) {
            PropertyRepository.updateForeignAddress(property.id, "2 High Street")
        }
    }

    @Test
    fun `updateForeignAddress throws for an unknown property id`() {
        assertThrows(IllegalArgumentException::class.java) {
            PropertyRepository.updateForeignAddress(999, "11 Rue de Paris")
        }
    }
}
