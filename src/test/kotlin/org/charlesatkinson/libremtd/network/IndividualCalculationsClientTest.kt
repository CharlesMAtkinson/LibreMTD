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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class IndividualCalculationsClientTest {

    @Test
    fun `the final declaration path ends in final-declaration`() {
        assertEquals(
            "/individuals/calculations/AA111111A/self-assessment/2026-27/calc-1/final-declaration",
            finalDeclarationPath("AA111111A", "2026-27", "calc-1"),
        )
    }

    @Test
    fun `the final declaration path no longer uses crystallisation`() {
        assertFalse(finalDeclarationPath("AA111111A", "2026-27", "calc-1").contains("crystallisation"))
    }
}
