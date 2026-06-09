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

import org.charlesatkinson.libremtd.database.tables.UiPreferences
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

object UiPreferencesRepository {

    // ── Keys ──────────────────────────────────────────────────────────────────

    const val KEY_LAST_EXPORT_DIR   = "export.lastDir"
    const val KEY_LAST_EXPORT_TABLE = "export.lastTable"
    const val KEY_LAST_HELP_TOPIC   = "help.lastTopic"
    const val KEY_LAST_PERIOD_ID    = "selector.lastPeriodId"
    const val KEY_LAST_PROPERTY_ID  = "selector.lastPropertyId"
    const val KEY_LAST_TAX_YEAR     = "selector.lastTaxYear"
    const val KEY_WIN_HEIGHT        = "window.height"
    const val KEY_WIN_WIDTH         = "window.width"
    const val KEY_WIN_X             = "window.x"
    const val KEY_WIN_Y             = "window.y"
    const val KEY_THEME             = "ui.theme"
    const val KEY_OAUTH_WIN_HEIGHT  = "oauth.window.height"
    const val KEY_OAUTH_WIN_WIDTH   = "oauth.window.width"
    const val KEY_OAUTH_WIN_X       = "oauth.window.x"
    const val KEY_OAUTH_WIN_Y       = "oauth.window.y"

    // ── Core get / set ────────────────────────────────────────────────────────

    fun get(userId: Int, key: String): String? =
        transaction {
            UiPreferences
                .selectAll()
                .where { (UiPreferences.userId eq userId) and (UiPreferences.key eq key) }
                .singleOrNull()
                ?.get(UiPreferences.value)
        }

    fun set(userId: Int, key: String, value: String?) {
        transaction {
            if (value == null) {
                UiPreferences.deleteWhere {
                    (UiPreferences.userId eq userId) and (UiPreferences.key eq key)
                }
            } else {
                UiPreferences.upsert {
                    it[UiPreferences.userId] = userId
                    it[UiPreferences.key]    = key
                    it[UiPreferences.value]  = value
                }
            }
        }
    }

    // ── Typed convenience helpers ─────────────────────────────────────────────

    fun getInt(userId: Int, key: String): Int?       = get(userId, key)?.toIntOrNull()
    fun getDouble(userId: Int, key: String): Double? = get(userId, key)?.toDoubleOrNull()

    fun setInt(userId: Int, key: String, value: Int?)       = set(userId, key, value?.toString())
    fun setDouble(userId: Int, key: String, value: Double?) = set(userId, key, value?.toString())
}