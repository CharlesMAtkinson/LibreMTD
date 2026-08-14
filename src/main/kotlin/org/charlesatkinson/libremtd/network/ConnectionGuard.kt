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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.charlesatkinson.libremtd.database.SettingsRepository
import org.charlesatkinson.libremtd.database.HmrcSettings
import org.charlesatkinson.libremtd.network.HmrcApiClient
import org.charlesatkinson.libremtd.security.TokenStore

data class ConnectionCheck(
    val settings: HmrcSettings,
    val client:   HmrcApiClient,
)

/**
 * Verifies NINO is set, HMRC token is valid, optionally that a business ID
 * is set, and that an API client is available — in that order, matching the
 * order the four call sites in this package previously checked them in.
 *
 * On the first failed check, [onFailure] is called with a user-facing
 * message and this function returns null. [onFailure] is responsible for
 * any UI thread-marshalling (Platform.runLater) the caller needs.
 */
suspend fun requireConnected(
    userId:              Int,
    settingsRepository:  SettingsRepository,
    getApiClient:        suspend () -> HmrcApiClient?,
    requireBusinessId:   Boolean = false,
    onFailure:           (String) -> Unit,
): ConnectionCheck? {
    val settings = withContext(Dispatchers.IO) { settingsRepository.load(userId) }

    if (settings == null || settings.nino.isBlank()) {
        onFailure("NINO not set — go to Settings")
        return null
    }
    if (TokenStore.isExpired() || TokenStore.getAccessToken() == null) {
        onFailure("Not connected to HMRC")
        return null
    }
    if (requireBusinessId && settings.businessId.isBlank()) {
        onFailure("Business ID not set — go to Settings")
        return null
    }
    val client = getApiClient() ?: run {
        onFailure("API client not configured — check Settings")
        return null
    }
    return ConnectionCheck(settings, client)
}