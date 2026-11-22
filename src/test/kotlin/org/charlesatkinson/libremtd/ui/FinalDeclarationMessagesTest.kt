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

import org.charlesatkinson.libremtd.ui.components.ForeignBsasPropertyBlock
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure functions only: no JavaFX toolkit and no database.
 */
class FinalDeclarationMessagesTest {

    private fun block(id: String, matched: Boolean, country: String? = "FRA") = ForeignBsasPropertyBlock(
        propertyId           = id,
        countryCode          = country,
        label                = if (matched) "10 Rue de Paris (FRA)" else "Unrecognised property (HMRC property ID $id)",
        matchedLocalProperty = matched,
        summary              = null,
        existingIncome       = emptyMap(),
        existingExpenses     = emptyMap(),
    )

    // ── nothing to adjust ─────────────────────────────────────────────────

    @Test
    fun `the no summary message names the tax year and says nothing was to be adjusted`() {
        val text = noAdjustableSummaryMessage("2026-27")

        assertTrue(text.contains("HMRC has no adjustable summary for 2026-27, so there was nothing to adjust."))
    }

    @Test
    fun `the no summary message says what happens next`() {
        assertTrue(noAdjustableSummaryMessage("2026-27").contains("request the final tax calculation"))
    }

    @Test
    fun `the no properties message names the tax year and says what happens next`() {
        val text = noPropertiesMessage("2026-27")

        assertTrue(text.contains("no properties"))
        assertTrue(text.contains("2026-27"))
        assertTrue(text.contains("request the final tax calculation"))
    }

    // ── unrecognised properties ───────────────────────────────────────────

    @Test
    fun `there is no notice when every property is recognised or there are none`() {
        assertNull(unrecognisedPropertiesMessage(emptyList()))
        assertNull(unrecognisedPropertiesMessage(listOf(block("p-1", matched = true))))
    }

    @Test
    fun `one unrecognised property gives a singular notice naming its id and country`() {
        val text = unrecognisedPropertiesMessage(listOf(block("abc-123", matched = false, country = "CYM")))

        assertNotNull(text)
        text!!
        assertTrue(text.startsWith("One property in HMRC's adjustable summary is not recognised by LibreMTD"))
        assertTrue(text.contains("HMRC property ID abc-123 (country CYM)"))
        assertTrue(text.contains("It may have been deleted"))
        assertTrue(text.contains("\"Unrecognised property\""))
    }

    @Test
    fun `several unrecognised properties give a plural notice listing each`() {
        val text = unrecognisedPropertiesMessage(
            listOf(block("p-1", matched = false), block("p-2", matched = false, country = "DEU"))
        )!!

        assertTrue(text.startsWith("Two properties in HMRC's adjustable summary are not recognised"))
        assertTrue(text.contains("HMRC property ID p-1 (country FRA)"))
        assertTrue(text.contains("HMRC property ID p-2 (country DEU)"))
        assertTrue(text.contains("They may have been deleted"))
    }

    @Test
    fun `recognised properties are not listed in the notice`() {
        val text = unrecognisedPropertiesMessage(
            listOf(block("known-id", matched = true), block("unknown-id", matched = false))
        )!!

        assertTrue(text.contains("HMRC property ID unknown-id"))
        // Not "known-id (country": that is a substring of "unknown-id (country".
        assertFalse(text.contains("HMRC property ID known-id"))
    }

    @Test
    fun `a missing country is described rather than shown as null`() {
        val text = unrecognisedPropertiesMessage(listOf(block("p-1", matched = false, country = null)))!!

        assertTrue(text.contains("country not reported by HMRC"))
        assertFalse(text.contains("null"))
    }

    @Test
    fun `the messages use no em-dashes`() {
        val all = listOf(
            noAdjustableSummaryMessage("2026-27"),
            noPropertiesMessage("2026-27"),
            unrecognisedPropertiesMessage(listOf(block("p-1", matched = false)))!!,
        )

        all.forEach { assertFalse(it.contains('\u2014'), it) }
    }
}
