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

package org.charlesatkinson.libremtd.database.tables

import org.jetbrains.exposed.sql.Table

// One row per (property, tax year) change of election. Holds year-scoped
// elections that apply to the whole property rather than a dated transaction
// — currently just foreignTaxCreditRelief, which HMRC's foreign property
// cumulative payload requires as a boolean alongside the income figures.
// The taxpayer can change this election at any time before Final
// Declaration, since each quarterly cumulative submission replaces the
// previous one — so this follows the same append/supersede history pattern
// as Properties and the *Entries tables, rather than being a single mutable row.
object ForeignPropertyElections : Table("foreign_property_elections") {
    val id                     = integer("id").autoIncrement()
    val propertyId             = integer("property_id").references(Properties.id)
    val taxYear                = text("tax_year")
    val foreignTaxCreditRelief = bool("foreign_tax_credit_relief")
    val recordedAt             = text("recorded_at")
    val supersededAt           = text("superseded_at").nullable()

    // Non-null only when this row was created automatically by rolling
    // forward the prior year's election at submission time, rather than by
    // an explicit user action in the Properties pane. Lets the UI/history
    // view distinguish "the user set this" from "LibreMTD carried it over".
    val rolledForwardFromTaxYear = text("rolled_forward_from_tax_year").nullable()

    override val primaryKey = PrimaryKey(id)
}
