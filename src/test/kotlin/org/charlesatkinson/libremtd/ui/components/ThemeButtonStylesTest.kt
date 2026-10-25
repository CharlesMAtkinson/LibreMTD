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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reads the three theme stylesheets from the classpath as plain text and
 * checks the button rules, so no JavaFX toolkit is needed.
 *
 * These tests exist because the app once used "secondary-action-button"
 * in many panes while no theme had a rule for it. The buttons silently
 * fell back to the plain .button style, which looked like a half-disabled
 * primary button.
 */
class ThemeButtonStylesTest {

    private val themes = listOf("dark-theme.css", "green-theme.css", "light-theme.css")
    private val kinds  = listOf("primary-action-button", "secondary-action-button", "danger-action-button")

    /**
     * Parses a stylesheet into selector -> declarations. Comments are
     * removed first. A selector list such as ".a:disabled, .b:disabled" is
     * split, so each selector maps to the shared declarations. If a
     * selector appears in several rules their declarations are joined.
     */
    private fun parse(file: String): Map<String, String> {
        val text = javaClass.getResourceAsStream("/styles/$file")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Stylesheet not found on the classpath: /styles/$file")

        val withoutComments = text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        val rules = mutableMapOf<String, String>()
        Regex("([^{}]+)\\{([^{}]*)\\}").findAll(withoutComments).forEach { match ->
            val declarations = match.groupValues[2].replace(Regex("\\s+"), " ").trim()
            match.groupValues[1].split(",")
                .map { it.trim().replace(Regex("\\s+"), " ") }
                .filter { it.isNotEmpty() }
                .forEach { selector -> rules[selector] = ((rules[selector] ?: "") + " " + declarations).trim() }
        }
        return rules
    }

    @Test
    fun `every theme styles every action button kind and its hover, pressed and disabled states`() {
        for (theme in themes) {
            val rules = parse(theme)
            for (kind in kinds) {
                for (suffix in listOf("", ":hover", ":pressed", ":disabled")) {
                    assertTrue(".$kind$suffix" in rules, "$theme has no rule for .$kind$suffix")
                }
            }
        }
    }

    @Test
    fun `the plain button has a disabled rule in every theme`() {
        for (theme in themes) {
            assertTrue(".button:disabled" in parse(theme), "$theme has no rule for .button:disabled")
        }
    }

    @Test
    fun `secondary buttons are outlined, not filled`() {
        for (theme in themes) {
            val declarations = parse(theme).getValue(".secondary-action-button")

            assertTrue(
                declarations.contains("-fx-background-color: transparent"),
                "$theme: secondary buttons must have a transparent fill, but have: $declarations",
            )
            val border = Regex("-fx-border-color:\\s*([^;]+);").find(declarations)?.groupValues?.get(1)?.trim()
            assertNotNull(border, "$theme: secondary buttons must declare a border colour")
            assertNotEquals("transparent", border, "$theme: the secondary border must be visible")
        }
    }

    @Test
    fun `secondary text matches its outline so the button reads as one colour`() {
        for (theme in themes) {
            val declarations = parse(theme).getValue(".secondary-action-button")
            val border = Regex("-fx-border-color:\\s*([^;]+);").find(declarations)?.groupValues?.get(1)?.trim()
            val text   = Regex("-fx-text-fill:\\s*([^;]+);").find(declarations)?.groupValues?.get(1)?.trim()

            assertEquals(border, text, "$theme: secondary border and text colour differ")
        }
    }

    @Test
    fun `every disabled button looks the same, flat grey with no border and full opacity`() {
        for (theme in themes) {
            val rules    = parse(theme)
            val expected = rules.getValue(".button:disabled")

            for (kind in kinds) {
                assertEquals(expected, rules.getValue(".$kind:disabled"), "$theme: .$kind:disabled differs from .button:disabled")
            }
            assertTrue(expected.contains("-fx-border-color: transparent"), "$theme: disabled buttons must have no visible border")
            assertTrue(expected.contains("-fx-opacity: 1.0"), "$theme: disabled buttons must override the global opacity")
        }
    }

    @Test
    fun `a disabled button never uses the secondary outline colour`() {
        for (theme in themes) {
            val rules    = parse(theme)
            val outline  = Regex("-fx-border-color:\\s*([^;]+);")
                .find(rules.getValue(".secondary-action-button"))?.groupValues?.get(1)?.trim()
            val disabled = rules.getValue(".secondary-action-button:disabled")

            assertTrue(!disabled.contains(outline!!), "$theme: the disabled style must not use the accent colour $outline")
        }
    }
}
