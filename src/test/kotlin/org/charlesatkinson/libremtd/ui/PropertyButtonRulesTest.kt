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

package org.charlesatkinson.libremtd.ui

import org.charlesatkinson.libremtd.database.Property
import org.charlesatkinson.libremtd.database.PropertyType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * canEndLetting and canRegisterWithHmrc are pure functions, so unlike the
 * pane itself they need no JavaFX toolkit to test.
 */
class PropertyButtonRulesTest {

    private fun property(
        type: PropertyType,
        endedAt: String? = null,
        hmrcPropertyId: String? = null,
    ) = Property(
        id = 1, userId = 1, address = "1 High Street",
        postcode = if (type == PropertyType.UK) "AB1 2CD" else null,
        propertyType = type,
        countryCode = if (type == PropertyType.FOREIGN) "FRA" else null,
        hmrcPropertyId = hmrcPropertyId, hmrcRegisteredAt = null, hmrcRegisteredTaxYear = null,
        createdAt = "2026-04-06T00:00:00", endedAt = endedAt, supersededAt = null,
    )

    // ── End letting ───────────────────────────────────────────────────────

    @Test
    fun `a property still being let can have its letting ended`() {
        assertTrue(canEndLetting(property(PropertyType.UK)))
        assertTrue(canEndLetting(property(PropertyType.FOREIGN, hmrcPropertyId = "abc")))
    }

    @Test
    fun `a property which has already ended cannot have its letting ended again`() {
        assertFalse(canEndLetting(property(PropertyType.UK, endedAt = "2026-08-01T00:00:00")))
        assertFalse(canEndLetting(property(PropertyType.FOREIGN, endedAt = "2026-08-01T00:00:00", hmrcPropertyId = "abc")))
    }

    // ── Register with HMRC ────────────────────────────────────────────────

    @Test
    fun `an unregistered foreign property which is still let can be registered`() {
        assertTrue(canRegisterWithHmrc(property(PropertyType.FOREIGN)))
    }

    @Test
    fun `a foreign property which is already registered cannot be registered again`() {
        assertFalse(canRegisterWithHmrc(property(PropertyType.FOREIGN, hmrcPropertyId = "abc")))
    }

    @Test
    fun `a foreign property which has ended cannot be registered`() {
        assertFalse(canRegisterWithHmrc(property(PropertyType.FOREIGN, endedAt = "2026-08-01T00:00:00")))
    }

    @Test
    fun `a UK property is never registered with HMRC`() {
        assertFalse(canRegisterWithHmrc(property(PropertyType.UK)))
    }
}
