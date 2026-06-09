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

import org.charlesatkinson.libremtd.database.UiPreferencesRepository
import org.charlesatkinson.libremtd.ui.components.UiTheme

/**
 * Per-user UI preference facade.  Construct once after login and pass to
 * any component that needs to persist lightweight UI state.
 *
 * All reads return null (or the type default) on a missing key.
 * All writes are best-effort; Exposed exceptions propagate normally.
 */
class UiPreferences(private val userId: Int) {

    var lastExportDir: String?
        get() = UiPreferencesRepository.get(userId, UiPreferencesRepository.KEY_LAST_EXPORT_DIR)
        set(value) = UiPreferencesRepository.set(userId, UiPreferencesRepository.KEY_LAST_EXPORT_DIR, value)

    var lastExportTable: String?
        get() = UiPreferencesRepository.get(userId, UiPreferencesRepository.KEY_LAST_EXPORT_TABLE)
        set(value) = UiPreferencesRepository.set(userId, UiPreferencesRepository.KEY_LAST_EXPORT_TABLE, value)

    var lastHelpTopic: String?
        get() = UiPreferencesRepository.get(userId, UiPreferencesRepository.KEY_LAST_HELP_TOPIC)
        set(value) = UiPreferencesRepository.set(userId, UiPreferencesRepository.KEY_LAST_HELP_TOPIC, value)

    var lastPeriodId: Int?
        get() = UiPreferencesRepository.getInt(userId, UiPreferencesRepository.KEY_LAST_PERIOD_ID)
        set(value) = UiPreferencesRepository.setInt(userId, UiPreferencesRepository.KEY_LAST_PERIOD_ID, value)

    var lastPropertyId: Int?
        get() = UiPreferencesRepository.getInt(userId, UiPreferencesRepository.KEY_LAST_PROPERTY_ID)
        set(value) = UiPreferencesRepository.setInt(userId, UiPreferencesRepository.KEY_LAST_PROPERTY_ID, value)

    var lastTaxYear: String?
        get() = UiPreferencesRepository.get(userId, UiPreferencesRepository.KEY_LAST_TAX_YEAR)
        set(value) = UiPreferencesRepository.set(userId, UiPreferencesRepository.KEY_LAST_TAX_YEAR, value)

    var windowX: Double?
        get() = UiPreferencesRepository.getDouble(userId, UiPreferencesRepository.KEY_WIN_X)
        set(value) = UiPreferencesRepository.setDouble(userId, UiPreferencesRepository.KEY_WIN_X, value)

    var windowY: Double?
        get() = UiPreferencesRepository.getDouble(userId, UiPreferencesRepository.KEY_WIN_Y)
        set(value) = UiPreferencesRepository.setDouble(userId, UiPreferencesRepository.KEY_WIN_Y, value)

    var windowWidth: Double?
        get() = UiPreferencesRepository.getDouble(userId, UiPreferencesRepository.KEY_WIN_WIDTH)
        set(value) = UiPreferencesRepository.setDouble(userId, UiPreferencesRepository.KEY_WIN_WIDTH, value)

    var windowHeight: Double?
        get() = UiPreferencesRepository.getDouble(userId, UiPreferencesRepository.KEY_WIN_HEIGHT)
        set(value) = UiPreferencesRepository.setDouble(userId, UiPreferencesRepository.KEY_WIN_HEIGHT, value)

    var theme: UiTheme
        get() = UiPreferencesRepository.get(userId, UiPreferencesRepository.KEY_THEME)
            ?.let { name -> UiTheme.entries.firstOrNull { it.name == name } }
            ?: UiTheme.LIGHT
        set(value) = UiPreferencesRepository.set(userId, UiPreferencesRepository.KEY_THEME, value.name)

    var oauthWindowX: Double?
        get() = UiPreferencesRepository.getDouble(userId, UiPreferencesRepository.KEY_OAUTH_WIN_X)
        set(value) = UiPreferencesRepository.setDouble(userId, UiPreferencesRepository.KEY_OAUTH_WIN_X, value)

    var oauthWindowY: Double?
        get() = UiPreferencesRepository.getDouble(userId, UiPreferencesRepository.KEY_OAUTH_WIN_Y)
        set(value) = UiPreferencesRepository.setDouble(userId, UiPreferencesRepository.KEY_OAUTH_WIN_Y, value)

    var oauthWindowWidth: Double?
        get() = UiPreferencesRepository.getDouble(userId, UiPreferencesRepository.KEY_OAUTH_WIN_WIDTH)
        set(value) = UiPreferencesRepository.setDouble(userId, UiPreferencesRepository.KEY_OAUTH_WIN_WIDTH, value)

    var oauthWindowHeight: Double?
        get() = UiPreferencesRepository.getDouble(userId, UiPreferencesRepository.KEY_OAUTH_WIN_HEIGHT)
        set(value) = UiPreferencesRepository.setDouble(userId, UiPreferencesRepository.KEY_OAUTH_WIN_HEIGHT, value)
}