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

package org.charlesatkinson.libremtd.database.components

import org.charlesatkinson.libremtd.database.PeriodRepository
import org.charlesatkinson.libremtd.database.SubmissionRepository

/**
 * Central enforcement point for the rule that no income, expense, allowance
 * or foreign property election entry may be created, edited or deleted for
 * a tax year once that year has been finally declared to HMRC (see
 * SubmissionRepository.isFinalDeclared).
 *
 * Called from the entry repositories themselves, at the start of their
 * transaction blocks — not only from the UI — so that no save path,
 * present or future, can bypass it. UI code should also check ahead of
 * time (e.g. to disable controls) but must not rely on that check alone.
 */
object FinalDeclarationGuard {

    /** Throws FinalDeclarationLockedException if [taxYear] is already
     *  finally declared for [userId]. */
    fun requireNotFinalDeclared(userId: Int, taxYear: String) {
        if (SubmissionRepository.isFinalDeclared(userId, taxYear)) {
            throw FinalDeclarationLockedException(taxYear)
        }
    }

    /** As [requireNotFinalDeclared], but resolves the tax year from
     *  [periodId] first, for repositories keyed on period rather than tax
     *  year directly (the property income/expense repositories). Does
     *  nothing if [periodId] does not correspond to a known period, since
     *  in that case there is nothing to guard against. */
    fun requireNotFinalDeclaredForPeriod(userId: Int, periodId: Int) {
        val taxYear = PeriodRepository.findById(periodId)?.taxYear ?: return
        requireNotFinalDeclared(userId, taxYear)
    }
}
