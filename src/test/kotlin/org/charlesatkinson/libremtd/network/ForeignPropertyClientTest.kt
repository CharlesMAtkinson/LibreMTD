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

package org.charlesatkinson.libremtd.network

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure serialisation tests — no HTTP involved. These exist specifically to
 * prove the explicitNulls = false fix on the shared [json] instance: a
 * rename-only request must not carry "endDate"/"endReason" at all, since
 * that is what distinguishes a plain rename from an end-of-letting request
 * to HMRC's Update Foreign Property Details endpoint.
 */
class ForeignPropertyClientTest {

    @Test
    fun `a rename-only request omits endDate and endReason from the JSON body`() {
        val request = UpdateForeignPropertyRequest(propertyName = "Bob & Bobby Co")

        val encoded = json.encodeToString(UpdateForeignPropertyRequest.serializer(), request)

        assertTrue(encoded.contains("Bob & Bobby Co"), "Expected propertyName in: $encoded")
        assertFalse(encoded.contains("endDate"), "Expected no endDate key in: $encoded")
        assertFalse(encoded.contains("endReason"), "Expected no endReason key in: $encoded")
    }

    @Test
    fun `an end-of-letting request includes endDate and endReason in the JSON body`() {
        val request = UpdateForeignPropertyRequest(
            propertyName = "Bob & Bobby Co",
            endDate      = "2026-08-24",
            endReason    = "no-longer-renting-property-out",
        )

        val encoded = json.encodeToString(UpdateForeignPropertyRequest.serializer(), request)

        assertTrue(encoded.contains("2026-08-24"), "Expected endDate in: $encoded")
        assertTrue(encoded.contains("no-longer-renting-property-out"), "Expected endReason in: $encoded")
    }
}
