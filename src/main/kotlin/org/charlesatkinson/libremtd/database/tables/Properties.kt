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

object Properties : Table("properties") {
    val id                    = integer("id").autoIncrement()
    val userId                = integer("user_id").references(Users.id)
    val address               = text("address")
    val postcode              = text("postcode").nullable()
    val propertyType          = text("property_type")
    val countryCode           = text("country_code").nullable()
    val hmrcPropertyId        = text("hmrc_property_id").nullable()

    // When and under which tax year a foreign property was registered with
    // HMRC (i.e. issued a propertyId via Create/Register Foreign Property
    // Details). hmrcRegisteredTaxYear is needed because the Update/End
    // Foreign Property Details endpoint is scoped to {taxYear} in its path —
    // that must be the *original* registration year, since registration is
    // understood to carry forward across later tax years rather than being
    // repeated. Both null for UK properties and for foreign properties not
    // yet registered with HMRC (2025-26 local-only entries, or 2026-27+
    // entries not yet registered).
    val hmrcRegisteredAt      = text("hmrc_registered_at").nullable()
    val hmrcRegisteredTaxYear = text("hmrc_registered_tax_year").nullable()

    val createdAt             = text("created_at")

    // NOTE: was previously declared without .nullable() even though create()
    // never sets it and findByUser() filters on Properties.supersededAt.isNull().
    // Added .nullable() here so the Exposed declaration matches how the
    // column is actually used; this doesn't require a DB migration.
    val supersededAt          = text("superseded_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
