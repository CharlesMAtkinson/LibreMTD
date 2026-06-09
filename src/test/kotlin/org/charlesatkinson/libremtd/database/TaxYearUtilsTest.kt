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

import org.charlesatkinson.libremtd.database.taxYearForDate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TaxYearUtilsTest {

    @Test
    fun `tax year containing April 5 is the year ending on that date`() {
        val result = taxYearForDate("2026-04-05")
        assertEquals("2025-26", result)
    }

    @Test
    fun `tax year containing April 6 is the following year`() {
        val result = taxYearForDate("2026-04-06")
        assertEquals("2026-27", result)
    }
}