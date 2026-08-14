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

data class HmrcSettings(
    val userId: Int,
    val clientId: String,
    val clientSecret: String,
    val nino: String,
    val utr: String,
    val businessId: String,
    val fullName: String,
    val dateOfBirth: String,
    val addressLine1: String,
    val addressLine2: String,
    val addressLine3: String,
    val postcode: String,
    val businessIdForeign: String,
)