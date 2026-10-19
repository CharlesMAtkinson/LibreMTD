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

package org.charlesatkinson.libremtd.ui.components

import org.charlesatkinson.libremtd.database.Property
import org.charlesatkinson.libremtd.database.PropertyType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * propertySelectorLabel is a pure function, so unlike the selector itself
 * it needs no JavaFX toolkit to test.
 */
class PropertySelectorLabelTest {

    private fun property(
        address: String,
        type: PropertyType,
        postcode: String? = null,
        countryCode: String? = null,
        endedAt: String? = null,
    ) = Property(
        id = 1, userId = 1, address = address, postcode = postcode, propertyType = type,
        countryCode = countryCode, hmrcPropertyId = null, hmrcRegisteredAt = null,
        hmrcRegisteredTaxYear = null, createdAt = "2026-04-06T00:00:00", endedAt = endedAt,
        supersededAt = null,
    )

    @Test
    fun `a UK property has no comma before the postcode`() {
        val p = property("42 Imaginary Crescent, Madeup-on-Sea", PropertyType.UK, postcode = "MS2 7UP")

        assertEquals("42 Imaginary Crescent, Madeup-on-Sea MS2 7UP", propertySelectorLabel(p))
    }

    @Test
    fun `a foreign property shows its country code in parentheses`() {
        val p = property("10 Rue de Paris", PropertyType.FOREIGN, countryCode = "FRA")

        assertEquals("10 Rue de Paris (FRA)", propertySelectorLabel(p))
    }

    @Test
    fun `an ended property is marked as ended`() {
        val p = property("1 High Street", PropertyType.UK, postcode = "AB1 2CD", endedAt = "2026-08-01T00:00:00")

        assertEquals("1 High Street AB1 2CD (ended)", propertySelectorLabel(p))
    }
}
