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

import org.charlesatkinson.libremtd.database.tables.Periods
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate

data class Period(
    val id: Int,
    val taxYear: String,
    val periodKey: String,
    val startDate: String,
    val endDate: String,
    val dueDate: String,
)

/**
 * One of the four standard update periods (6 Apr to 5 Jul, 6 Jul to
 * 5 Oct, 6 Oct to 5 Jan, 6 Jan to 5 Apr) for a tax year. Pure calendar
 * arithmetic with no database or network involvement.
 *
 * Named "standard" deliberately: HMRC also offers "calendar update
 * periods" (1 Apr to 31 Mar, ending on the last day of a month). Support
 * for those is a planned but deferred feature, and would add a parallel
 * calendar function alongside this one rather than changing it.
 */
data class StandardQuarter(
    val taxYear: String,
    val periodKey: String,
    val startDate: String,
    val endDate: String,
    val dueDate: String,
)

/**
 * The four standard update quarters for [taxYear] (#001 .. #004), in
 * order. Single source of truth for standard quarter boundary
 * arithmetic — [standardQuarterFor] and [standardQuarterBounds] both
 * derive from this, so the boundary dates and the "due on the 7th of the
 * following month" rule exist in exactly one place.
 *
 * Returns null if [taxYear] is not a recognisable "YYYY-YY" string.
 */
private fun standardQuartersFor(taxYear: String): List<StandardQuarter>? {
    val startYear = taxYear.take(4).toIntOrNull() ?: return null
    val bounds = listOf(
        LocalDate.of(startYear, 4, 6)     to LocalDate.of(startYear, 7, 5),
        LocalDate.of(startYear, 7, 6)     to LocalDate.of(startYear, 10, 5),
        LocalDate.of(startYear, 10, 6)    to LocalDate.of(startYear + 1, 1, 5),
        LocalDate.of(startYear + 1, 1, 6) to LocalDate.of(startYear + 1, 4, 5),
    )
    return bounds.mapIndexed { i, (start, end) ->
        StandardQuarter(
            taxYear   = taxYear,
            periodKey = "#00${i + 1}",
            startDate = start.toString(),
            endDate   = end.toString(),
            dueDate   = end.plusMonths(1).withDayOfMonth(7).toString(),
        )
    }
}

/**
 * Returns the standard quarter containing [isoDate] (format YYYY-MM-DD).
 * The four quarters are contiguous and cover every day of the tax year,
 * so every date maps to exactly one quarter.
 */
fun standardQuarterFor(isoDate: String): StandardQuarter {
    val date    = LocalDate.parse(isoDate)
    val taxYear = taxYearForDate(isoDate)
    val quarters = standardQuartersFor(taxYear)
        ?: error("taxYearForDate produced an unparseable tax year: $taxYear")
    return quarters.first { !date.isAfter(LocalDate.parse(it.endDate)) }
}

/**
 * Returns the discrete (non-overlapping) start/end dates for [periodKey]
 * within [taxYear], or null if [periodKey] is not one of the four
 * standard quarter codes ("#001".."#004") or [taxYear] is not
 * recognisable.
 *
 * Used when persisting a Period from an HMRC obligation whose own dates
 * may be cumulative (year to date) rather than one discrete quarter — see
 * network.buildObligation's doc comment. Moved here (previously
 * ui.components.TaxQuarterUtils) so the quarter-boundary arithmetic it
 * shares with [standardQuarterFor] lives in one place rather than two.
 */
fun standardQuarterBounds(taxYear: String, periodKey: String): Pair<String, String>? {
    val quarters = standardQuartersFor(taxYear) ?: return null
    return quarters.firstOrNull { it.periodKey == periodKey }
        ?.let { it.startDate to it.endDate }
}

object PeriodRepository {

    fun upsert(
        taxYear: String,
        periodKey: String,
        startDate: String,
        endDate: String,
        dueDate: String,
    ): Period {
        return transaction {
            val existing = Periods
                .selectAll()
                .where { (Periods.taxYear eq taxYear) and
                        (Periods.periodKey eq periodKey) }
                .singleOrNull()

            if (existing != null) {
                val id = existing[Periods.id]

                Periods.update({ Periods.id eq id }) {
                    it[Periods.startDate] = startDate
                    it[Periods.endDate]   = endDate
                    it[Periods.dueDate]   = dueDate
                }

                Period(
                    id         = id,
                    taxYear    = taxYear,
                    periodKey  = periodKey,
                    startDate  = startDate,
                    endDate    = endDate,
                    dueDate    = dueDate,
                )
            } else {
                val id = Periods.insert {
                    it[Periods.taxYear]    = taxYear
                    it[Periods.periodKey]  = periodKey
                    it[Periods.startDate]  = startDate
                    it[Periods.endDate]    = endDate
                    it[Periods.dueDate]    = dueDate
                } get Periods.id

                Period(id, taxYear, periodKey, startDate, endDate, dueDate)
            }
        }
    }

    /**
     * Returns the local Period row for the standard quarter containing
     * [isoDate], creating it if it does not exist yet.
     *
     * This exists so that recording an income or expense entry never
     * depends on HMRC obligations having been fetched first: the entry
     * tables still carry a period_id foreign key, and this satisfies it
     * from calendar arithmetic alone.
     *
     * An existing row is returned unchanged and never overwritten, so
     * figures previously stored from HMRC's obligations (see
     * ObligationsSection) take precedence over the locally calculated
     * ones.
     */
    fun getOrCreateStandard(isoDate: String): Period {
        val q = standardQuarterFor(isoDate)
        return transaction {
            val existing = Periods
                .selectAll()
                .where { (Periods.taxYear eq q.taxYear) and (Periods.periodKey eq q.periodKey) }
                .singleOrNull()

            if (existing != null) {
                existing.toPeriod()
            } else {
                val id = Periods.insert {
                    it[Periods.taxYear]   = q.taxYear
                    it[Periods.periodKey] = q.periodKey
                    it[Periods.startDate] = q.startDate
                    it[Periods.endDate]   = q.endDate
                    it[Periods.dueDate]   = q.dueDate
                } get Periods.id

                Period(id, q.taxYear, q.periodKey, q.startDate, q.endDate, q.dueDate)
            }
        }
    }

    fun findAll(): List<Period> {
        return transaction {
            Periods
                .selectAll()
                .orderBy(Periods.taxYear to SortOrder.ASC, Periods.periodKey to SortOrder.ASC)
                .map { row -> row.toPeriod() }
        }
    }

    fun findById(id: Int): Period? {
        return transaction {
            Periods
                .selectAll()
                .where { Periods.id eq id }
                .singleOrNull()
                ?.toPeriod()
        }
    }

    fun findByTaxYear(taxYear: String): List<Period> {
        return transaction {
            Periods
                .selectAll()
                .where { (Periods.taxYear eq taxYear) }
                .map { row -> row.toPeriod() }
        }
    }

    private fun ResultRow.toPeriod() = Period(
        id        = this[Periods.id],
        taxYear   = this[Periods.taxYear],
        periodKey = this[Periods.periodKey],
        startDate = this[Periods.startDate],
        endDate   = this[Periods.endDate],
        dueDate   = this[Periods.dueDate],
    )
}
