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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextUtilsTest {

    @Test
    fun `numbers below ten are written as words`() {
        assertEquals(
            listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"),
            (0..9).map { countInWords(it) },
        )
    }

    @Test
    fun `ten and above are written as digits`() {
        assertEquals("10", countInWords(10))
        assertEquals("11", countInWords(11))
        assertEquals("250", countInWords(250))
    }

    @Test
    fun `negative numbers are written as digits`() {
        assertEquals("-1", countInWords(-1))
    }

    @Test
    fun `normalizeText joins lines and turns the NL marker into a line break`() {
        val result = normalizeText(
            """
                First line
                continues here.
                {NL}{NL}
                Second paragraph.
            """
        )

        assertEquals("First line continues here.\n\nSecond paragraph.", result)
    }
}
