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

data class Period(
    val id: Int,
    val taxYear: String,
    val periodKey: String,
    val startDate: String,
    val endDate: String,
    val dueDate: String,
)

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
                Period(
                    id         = existing[Periods.id],
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

    fun findAll(): List<Period> {
        return transaction {
            Periods
                .selectAll()
                .orderBy(Periods.taxYear to SortOrder.ASC, Periods.periodKey to SortOrder.ASC)
                .map { row ->
                    Period(
                        id        = row[Periods.id],
                        taxYear   = row[Periods.taxYear],
                        periodKey = row[Periods.periodKey],
                        startDate = row[Periods.startDate],
                        endDate   = row[Periods.endDate],
                        dueDate   = row[Periods.dueDate],
                    )
                }
        }
    }

    fun findById(id: Int): Period? {
        return transaction {
            Periods
                .selectAll()
                .where { Periods.id eq id }
                .singleOrNull()
                ?.let { row ->
                    Period(
                        id        = row[Periods.id],
                        taxYear   = row[Periods.taxYear],
                        periodKey = row[Periods.periodKey],
                        startDate = row[Periods.startDate],
                        endDate   = row[Periods.endDate],
                        dueDate   = row[Periods.dueDate],
                    )
                }
        }
    }

    fun findByTaxYear(taxYear: String): List<Period> {
        return transaction {
            Periods
                .selectAll()
                .where { (Periods.taxYear eq taxYear) }
                .map { row ->
                    Period(
                        id         = row[Periods.id],
                        taxYear    = row[Periods.taxYear],
                        periodKey  = row[Periods.periodKey],
                        startDate  = row[Periods.startDate],
                        endDate    = row[Periods.endDate],
                        dueDate    = row[Periods.dueDate],
                    )
                }
        }
    }
}