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

package org.charlesatkinson.libremtd.ui.components

/* Usage example:
normalizeText(
"""
    Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod.
    {NL}{NL}
    Duis aute irure dolor in reprehenderit in voluptate velit esse cillum
    dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non
    proident, sunt in culpa qui officia deserunt mollit anim id est laborum.
    {NL}{NL}
    Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi
    ut aliquip ex ea commodo consequat.
  """
)*/

/**
 * Joins the lines of [raw] into running text and turns each {NL} marker
 * into a line break. One space on either side of a marker is removed with
 * it, so a break never leaves a trailing or leading space behind.
 */
fun normalizeText(raw: String): String =
    raw.trimIndent()
        .lines()
        .joinToString(" ") { it.trim() }
        .replace(Regex(""" ?\{NL} ?"""), "\n")

private val SMALL_NUMBER_WORDS =
    listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")

/**
 * Writes a count in running UI text following the project convention: a
 * word for 0 to 9, digits for 10 and above (and for negative numbers), so
 * "one file, six sheets" but "one file, 12 sheets". Not for statistics or
 * figures such as "1 inserted", where digits are clearer.
 */
fun countInWords(n: Int): String =
    if (n in SMALL_NUMBER_WORDS.indices) SMALL_NUMBER_WORDS[n] else n.toString()
