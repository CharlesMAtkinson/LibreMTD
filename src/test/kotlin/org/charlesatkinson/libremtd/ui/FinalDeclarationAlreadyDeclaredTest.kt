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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FinalDeclarationAlreadyDeclaredTest {

    @Test
    fun `the already declared notice names the tax year`() {
        assertTrue(alreadyDeclaredMessage("2026-27").contains("2026-27"))
    }

    @Test
    fun `the already declared notice says a declaration was made and that it can no longer be amended`() {
        val text = alreadyDeclaredMessage("2026-27")

        assertTrue(text.contains("final declaration has already been made"))
        assertTrue(text.contains("can no longer be amended"))
    }

    @Test
    fun `the already declared notice does not offer the button text`() {
        assertFalse(alreadyDeclaredMessage("2026-27").contains("Make final declaration"))
    }

    @Test
    fun `the already declared notice uses no em-dashes`() {
        assertFalse(alreadyDeclaredMessage("2026-27").contains('\u2014'))
    }
}
