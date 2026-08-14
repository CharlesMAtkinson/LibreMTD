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

import org.charlesatkinson.libremtd.database.tables.ExpensePropertyUkEntries
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class ExpensePropertyUkEntry(
    val id: Int,
    val periodId: Int,
    val userId: Int,
    val propertyId: Int,
    val category: String,
    val amount: Double,
    val description: String,
    val transactionDate: String,
    val recordedAt: String,
    val supersededAt: String? = null,
)

object ExpensePropertyUkRepository {

    fun record(
        periodId: Int,
        userId: Int,
        propertyId: Int,
        category: String,
        amount: Double,
        description: String,
        transactionDate: String,
    ): ExpensePropertyUkEntry {
        return transaction {
            val now = LocalDateTime.now().toString()
            val id = ExpensePropertyUkEntries.insert {
                it[ExpensePropertyUkEntries.periodId]        = periodId
                it[ExpensePropertyUkEntries.userId]          = userId
                it[ExpensePropertyUkEntries.propertyId]      = propertyId
                it[ExpensePropertyUkEntries.category]        = category
                it[ExpensePropertyUkEntries.amount]          = amount
                it[ExpensePropertyUkEntries.description]     = description
                it[ExpensePropertyUkEntries.transactionDate] = transactionDate
                it[ExpensePropertyUkEntries.recordedAt]      = now
                it[ExpensePropertyUkEntries.supersededAt]    = null
            } get ExpensePropertyUkEntries.id

            ExpensePropertyUkEntry(id, periodId, userId, propertyId, category, amount, description, transactionDate, now)
        }
    }

    fun edit(
        existingId: Int,
        periodId: Int,
        userId: Int,
        propertyId: Int,
        category: String,
        amount: Double,
        description: String,
        transactionDate: String,
    ): ExpensePropertyUkEntry {
        return transaction {
            val now = LocalDateTime.now().toString()

            ExpensePropertyUkEntries.update({ ExpensePropertyUkEntries.id eq existingId }) {
                it[ExpensePropertyUkEntries.supersededAt] = now
            }

            val id = ExpensePropertyUkEntries.insert {
                it[ExpensePropertyUkEntries.periodId]        = periodId
                it[ExpensePropertyUkEntries.userId]          = userId
                it[ExpensePropertyUkEntries.propertyId]      = propertyId
                it[ExpensePropertyUkEntries.category]        = category
                it[ExpensePropertyUkEntries.amount]          = amount
                it[ExpensePropertyUkEntries.description]     = description
                it[ExpensePropertyUkEntries.transactionDate] = transactionDate
                it[ExpensePropertyUkEntries.recordedAt]      = now
                it[ExpensePropertyUkEntries.supersededAt]    = null
            } get ExpensePropertyUkEntries.id

            ExpensePropertyUkEntry(id, periodId, userId, propertyId, category, amount, description, transactionDate, now)
        }
    }

    fun delete(id: Int) {
        transaction {
            ExpensePropertyUkEntries.update({ ExpensePropertyUkEntries.id eq id }) {
                it[supersededAt] = LocalDateTime.now().toString()
            }
        }
    }

    fun currentPropertyExpensesForYear(userId: Int, taxYear: String): List<ExpensePropertyUkEntry> {
        val (startDate, endDate) = taxYearDateRange(taxYear)
        return transaction {
            ExpensePropertyUkEntries
                .selectAll()
                .where {
                    (ExpensePropertyUkEntries.userId eq userId) and
                            (ExpensePropertyUkEntries.supersededAt.isNull()) and
                            (ExpensePropertyUkEntries.transactionDate greaterEq startDate) and
                            (ExpensePropertyUkEntries.transactionDate lessEq endDate)
                }
                .map { row -> row.toExpensePropertyUkEntry() }
        }
    }

    fun currentForPeriod(periodId: Int): List<ExpensePropertyUkEntry> {
        return transaction {
            ExpensePropertyUkEntries
                .selectAll()
                .where {
                    (ExpensePropertyUkEntries.periodId eq periodId) and
                            ExpensePropertyUkEntries.supersededAt.isNull()
                }
                .map { row -> row.toExpensePropertyUkEntry() }
        }
    }

    fun currentForPeriodAndProperty(periodId: Int, propertyId: Int): List<ExpensePropertyUkEntry> {
        return transaction {
            ExpensePropertyUkEntries
                .selectAll()
                .where {
                    (ExpensePropertyUkEntries.periodId eq periodId) and
                            (ExpensePropertyUkEntries.propertyId eq propertyId) and
                            ExpensePropertyUkEntries.supersededAt.isNull()
                }
                .map { row -> row.toExpensePropertyUkEntry() }
        }
    }

    fun historyForPeriod(periodId: Int): List<ExpensePropertyUkEntry> {
        return transaction {
            ExpensePropertyUkEntries
                .selectAll()
                .where { ExpensePropertyUkEntries.periodId eq periodId }
                .orderBy(ExpensePropertyUkEntries.recordedAt, SortOrder.ASC)
                .map { row -> row.toExpensePropertyUkEntry() }
        }
    }

    private fun ResultRow.toExpensePropertyUkEntry() = ExpensePropertyUkEntry(
        id              = this[ExpensePropertyUkEntries.id],
        periodId        = this[ExpensePropertyUkEntries.periodId],
        userId          = this[ExpensePropertyUkEntries.userId],
        propertyId      = this[ExpensePropertyUkEntries.propertyId],
        category        = this[ExpensePropertyUkEntries.category],
        amount          = this[ExpensePropertyUkEntries.amount],
        description     = this[ExpensePropertyUkEntries.description],
        transactionDate = this[ExpensePropertyUkEntries.transactionDate],
        recordedAt      = this[ExpensePropertyUkEntries.recordedAt],
        supersededAt    = this[ExpensePropertyUkEntries.supersededAt],
    )
}
