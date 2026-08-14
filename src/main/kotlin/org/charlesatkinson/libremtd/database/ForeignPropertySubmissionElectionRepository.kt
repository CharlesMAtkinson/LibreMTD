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

package org.charlesatkinson.libremtd.database

import org.charlesatkinson.libremtd.database.tables.ForeignPropertySubmissionElections
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class ForeignPropertySubmissionElection(
    val id: Int,
    val submissionId: Int,
    val propertyId: Int,
    val taxYear: String,
    val foreignTaxCreditRelief: Boolean,
    val recordedAt: String,
)

object ForeignPropertySubmissionElectionRepository {

    /**
     * Records the foreignTaxCreditRelief value that was actually sent to
     * HMRC as part of [submissionId]. Called once by the submission code
     * after a successful PUT for a foreign property — never by the UI, and
     * never updated afterwards. One row per property per submission.
     */
    fun record(
        submissionId: Int,
        propertyId: Int,
        taxYear: String,
        foreignTaxCreditRelief: Boolean,
    ): ForeignPropertySubmissionElection {
        return transaction {
            val now = LocalDateTime.now().toString()
            val id = ForeignPropertySubmissionElections.insert {
                it[ForeignPropertySubmissionElections.submissionId]           = submissionId
                it[ForeignPropertySubmissionElections.propertyId]             = propertyId
                it[ForeignPropertySubmissionElections.taxYear]                = taxYear
                it[ForeignPropertySubmissionElections.foreignTaxCreditRelief] = foreignTaxCreditRelief
                it[ForeignPropertySubmissionElections.recordedAt]             = now
            } get ForeignPropertySubmissionElections.id

            ForeignPropertySubmissionElection(id, submissionId, propertyId, taxYear, foreignTaxCreditRelief, now)
        }
    }

    /** What was actually submitted for a property/tax year, most recent
     *  submission last. Distinct from ForeignPropertyElectionRepository's
     *  historyFor, which tracks the editable setting rather than what was
     *  actually sent. */
    fun historyFor(propertyId: Int, taxYear: String): List<ForeignPropertySubmissionElection> {
        return transaction {
            ForeignPropertySubmissionElections
                .selectAll()
                .where {
                    (ForeignPropertySubmissionElections.propertyId eq propertyId) and
                            (ForeignPropertySubmissionElections.taxYear eq taxYear)
                }
                .orderBy(ForeignPropertySubmissionElections.recordedAt, SortOrder.ASC)
                .map { row -> row.toSubmissionElection() }
        }
    }

    private fun ResultRow.toSubmissionElection() = ForeignPropertySubmissionElection(
        id                     = this[ForeignPropertySubmissionElections.id],
        submissionId           = this[ForeignPropertySubmissionElections.submissionId],
        propertyId             = this[ForeignPropertySubmissionElections.propertyId],
        taxYear                = this[ForeignPropertySubmissionElections.taxYear],
        foreignTaxCreditRelief = this[ForeignPropertySubmissionElections.foreignTaxCreditRelief],
        recordedAt             = this[ForeignPropertySubmissionElections.recordedAt],
    )
}
