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

// Immutable record of the foreignTaxCreditRelief value actually sent to
// HMRC in a given submission, for a given property. Written once by the
// submission code after a successful PUT — never edited afterwards. This is
// what answers "what did I submit for Q2", as distinct from
// ForeignPropertyElections, which tracks the user's current/editable setting.
object ForeignPropertySubmissionElections : Table("foreign_property_submission_elections") {
    val id                     = integer("id").autoIncrement()
    val submissionId           = integer("submission_id").references(Submissions.id)
    val propertyId             = integer("property_id").references(Properties.id)
    val taxYear                = text("tax_year")
    val foreignTaxCreditRelief = bool("foreign_tax_credit_relief")
    val recordedAt             = text("recorded_at")

    override val primaryKey = PrimaryKey(id)
}
