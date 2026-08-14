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

import org.charlesatkinson.libremtd.database.tables.ForeignPropertyElections
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class ForeignPropertyElection(
    val id: Int,
    val propertyId: Int,
    val taxYear: String,
    val foreignTaxCreditRelief: Boolean,
    val recordedAt: String,
    val supersededAt: String? = null,
    val rolledForwardFromTaxYear: String? = null,
)

object ForeignPropertyElectionRepository {

    /** Explicit user action — e.g. the checkbox in the new-property form, or
     *  the Foreign Tax Credit Relief dialog in PropertiesPane. */
    fun set(propertyId: Int, taxYear: String, foreignTaxCreditRelief: Boolean): ForeignPropertyElection =
        insertNew(propertyId, taxYear, foreignTaxCreditRelief, rolledForwardFromTaxYear = null)

    /** Current election for a property/tax year, or null if none exists yet
     *  (including via roll-forward — this does NOT roll forward; see
     *  currentOrRollForward for that). */
    fun current(propertyId: Int, taxYear: String): ForeignPropertyElection? {
        return transaction {
            ForeignPropertyElections
                .selectAll()
                .where {
                    (ForeignPropertyElections.propertyId eq propertyId) and
                            (ForeignPropertyElections.taxYear eq taxYear) and
                            ForeignPropertyElections.supersededAt.isNull()
                }
                .map { row -> row.toElection() }
                .firstOrNull()
        }
    }

    /**
     * Called from the submission-building code, not the UI. If [taxYear]
     * already has a current election, returns it unchanged. Otherwise finds
     * the most recent prior tax year's election for this property and
     * carries it forward as a new row for [taxYear], flagged via
     * rolledForwardFromTaxYear. Returns null only if the property has no
     * election in any prior year either — meaning the user has never set
     * FTCR for this property, and the submission code should treat that as
     * "not yet decided" rather than defaulting silently, and should refuse
     * to submit until the user has made an explicit choice.
     */
    fun currentOrRollForward(propertyId: Int, taxYear: String): ForeignPropertyElection? {
        current(propertyId, taxYear)?.let { return it }

        val mostRecentPrior = transaction {
            ForeignPropertyElections
                .selectAll()
                .where {
                    (ForeignPropertyElections.propertyId eq propertyId) and
                            (ForeignPropertyElections.taxYear less taxYear) and
                            ForeignPropertyElections.supersededAt.isNull()
                }
                .orderBy(ForeignPropertyElections.taxYear, SortOrder.DESC)
                .limit(1)
                .map { row -> row.toElection() }
                .firstOrNull()
        } ?: return null

        return insertNew(
            propertyId, taxYear, mostRecentPrior.foreignTaxCreditRelief,
            rolledForwardFromTaxYear = mostRecentPrior.taxYear,
        )
    }

    /** Raw change history for a property/tax year — what the user's setting
     *  has been over time, including roll-forwards. Shown in the info popup
     *  next to the FTCR dialog's checkboxes. */
    fun historyFor(propertyId: Int, taxYear: String): List<ForeignPropertyElection> {
        return transaction {
            ForeignPropertyElections
                .selectAll()
                .where {
                    (ForeignPropertyElections.propertyId eq propertyId) and
                            (ForeignPropertyElections.taxYear eq taxYear)
                }
                .orderBy(ForeignPropertyElections.recordedAt, SortOrder.ASC)
                .map { row -> row.toElection() }
        }
    }

    /** All tax years for which this property has ever had an election
     *  (current or superseded). Used to work out the earliest year FTCR
     *  tracking exists for a property, alongside Property.createdAt. */
    fun taxYearsFor(propertyId: Int): List<String> {
        return transaction {
            ForeignPropertyElections
                .selectAll()
                .where { ForeignPropertyElections.propertyId eq propertyId }
                .map { it[ForeignPropertyElections.taxYear] }
                .distinct()
                .sorted()
        }
    }

    private fun insertNew(
        propertyId: Int, taxYear: String, foreignTaxCreditRelief: Boolean,
        rolledForwardFromTaxYear: String?,
    ): ForeignPropertyElection {
        return transaction {
            val now = LocalDateTime.now().toString()

            ForeignPropertyElections.update({
                (ForeignPropertyElections.propertyId eq propertyId) and
                        (ForeignPropertyElections.taxYear eq taxYear) and
                        ForeignPropertyElections.supersededAt.isNull()
            }) {
                it[ForeignPropertyElections.supersededAt] = now
            }

            val id = ForeignPropertyElections.insert {
                it[ForeignPropertyElections.propertyId]               = propertyId
                it[ForeignPropertyElections.taxYear]                  = taxYear
                it[ForeignPropertyElections.foreignTaxCreditRelief]   = foreignTaxCreditRelief
                it[ForeignPropertyElections.recordedAt]               = now
                it[ForeignPropertyElections.supersededAt]             = null
                it[ForeignPropertyElections.rolledForwardFromTaxYear] = rolledForwardFromTaxYear
            } get ForeignPropertyElections.id

            ForeignPropertyElection(id, propertyId, taxYear, foreignTaxCreditRelief, now, null, rolledForwardFromTaxYear)
        }
    }

    private fun ResultRow.toElection() = ForeignPropertyElection(
        id                       = this[ForeignPropertyElections.id],
        propertyId               = this[ForeignPropertyElections.propertyId],
        taxYear                  = this[ForeignPropertyElections.taxYear],
        foreignTaxCreditRelief   = this[ForeignPropertyElections.foreignTaxCreditRelief],
        recordedAt               = this[ForeignPropertyElections.recordedAt],
        supersededAt             = this[ForeignPropertyElections.supersededAt],
        rolledForwardFromTaxYear = this[ForeignPropertyElections.rolledForwardFromTaxYear],
    )
}
