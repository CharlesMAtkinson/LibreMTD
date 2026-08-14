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

import mu.KotlinLogging
import org.charlesatkinson.libremtd.database.tables.Submissions
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

private val logger = KotlinLogging.logger {}

object SubmissionRepository {

    /**
     * Records a completed submission in the database.
     *
     * @param userId         The local user ID.
     * @param periodId       The period this submission relates to, or null for final declarations.
     * @param taxYear        The tax year this submission relates to, format "YYYY-YY".
     * @param submissionType A short string describing the type, e.g. "cumulative" or "final_declaration".
     * @param hmrcResponse   The HTTP status code or body snippet returned by HMRC.
     */
    fun record(
        userId:         Int,
        periodId:       Int?,
        taxYear:        String,
        submissionType: String,
        hmrcResponse:   String,
    ) {
        transaction {
            Submissions.insert {
                it[Submissions.userId]         = userId
                it[Submissions.periodId]       = periodId
                it[Submissions.taxYear]        = taxYear
                it[Submissions.submissionType] = submissionType
                it[Submissions.submittedAt]    = Instant.now().toString()
                it[Submissions.hmrcResponse]   = hmrcResponse
            }
            logger.info { "Recorded submission: type=$submissionType userId=$userId taxYear=$taxYear periodId=$periodId" }
        }
    }

    /**
     * True if a successful ("204") final declaration submission has been
     * recorded for this user and tax year. Used to decide which tax years
     * are still "open" for a given property — e.g. for the Foreign Tax
     * Credit Relief dialog, which should only offer years that can still be
     * changed before they're locked in by Final Declaration.
     *
     * Note: this checks for a *recorded* successful submission, not for
     * whatever HMRC's own records currently say. If a final declaration was
     * made through some other means (e.g. HMRC's own online service,
     * bypassing LibreMTD), this will not know about it.
     */
    fun isFinalDeclared(userId: Int, taxYear: String): Boolean {
        return transaction {
            Submissions
                .selectAll()
                .where {
                    (Submissions.userId eq userId) and
                            (Submissions.taxYear eq taxYear) and
                            (Submissions.submissionType eq "final_declaration") and
                            (Submissions.hmrcResponse eq "204")
                }
                .limit(1)
                .any()
        }
    }
}
