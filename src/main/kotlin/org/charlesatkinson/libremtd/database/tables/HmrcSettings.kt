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

package org.charlesatkinson.libremtd.database.tables

import org.jetbrains.exposed.sql.Table

object HmrcSettings : Table("hmrc_settings") {
    val userId            = integer("user_id")
    val clientId          = text("client_id").default("")
    val clientSecret      = text("client_secret").default("")
    val nino              = text("nino").default("")
    val utr               = text("utr").default("")
    val businessId        = text("business_id").default("")
    val businessIdForeign = text("business_id_foreign").default("")
    val fullName          = text("full_name").default("")
    val dateOfBirth       = text("date_of_birth").default("")
    val addressLine1      = text("address_line1").default("")
    val addressLine2      = text("address_line2").default("")
    val addressLine3      = text("address_line3").default("")
    val postcode          = text("postcode").default("")
    val updatedAt         = text("updated_at").default("")

    override val primaryKey = PrimaryKey(userId)
}