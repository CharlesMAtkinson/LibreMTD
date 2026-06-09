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

import org.charlesatkinson.libremtd.database.tables.ExpenseEntries
import org.charlesatkinson.libremtd.database.tables.ExpenseEntries.supersededAt
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class ExpenseEntry(
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

object ExpenseRepository {

    fun record(
        periodId: Int,
        userId: Int,
        propertyId: Int,
        category: String,
        amount: Double,
        description: String,
        transactionDate: String,
    ): ExpenseEntry {
        return transaction {
            val now = LocalDateTime.now().toString()
            val id = ExpenseEntries.insert {
                it[ExpenseEntries.periodId]        = periodId
                it[ExpenseEntries.userId]          = userId
                it[ExpenseEntries.propertyId]      = propertyId
                it[ExpenseEntries.category]        = category
                it[ExpenseEntries.amount]          = amount
                it[ExpenseEntries.description]     = description
                it[ExpenseEntries.transactionDate] = transactionDate
                it[ExpenseEntries.recordedAt]      = now
                it[ExpenseEntries.supersededAt]    = null
            } get ExpenseEntries.id

            ExpenseEntry(id, periodId, userId, propertyId, category, amount, description, transactionDate, now)
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
    ): ExpenseEntry {
        return transaction {
            val now = LocalDateTime.now().toString()

            ExpenseEntries.update({ ExpenseEntries.id eq existingId }) {
                it[ExpenseEntries.supersededAt] = now
            }

            val id = ExpenseEntries.insert {
                it[ExpenseEntries.periodId]        = periodId
                it[ExpenseEntries.userId]          = userId
                it[ExpenseEntries.propertyId]      = propertyId
                it[ExpenseEntries.category]        = category
                it[ExpenseEntries.amount]          = amount
                it[ExpenseEntries.description]     = description
                it[ExpenseEntries.transactionDate] = transactionDate
                it[ExpenseEntries.recordedAt]      = now
                it[ExpenseEntries.supersededAt]    = null
            } get ExpenseEntries.id

            ExpenseEntry(id, periodId, userId, propertyId, category, amount, description, transactionDate, now)
        }
    }

    fun delete(id: Int) {
        transaction {
            ExpenseEntries.update({ ExpenseEntries.id eq id }) {
                it[supersededAt] = LocalDateTime.now().toString()
            }
        }
    }

    fun currentPropertyExpensesForYear(userId: Int, taxYear: String): List<ExpenseEntry> {
        val (startDate, endDate) = taxYearDateRange(taxYear)
        return transaction {
            ExpenseEntries
                .selectAll()
                .where {
                    (ExpenseEntries.userId eq userId) and
                            (ExpenseEntries.supersededAt.isNull()) and
                            (ExpenseEntries.transactionDate greaterEq startDate) and
                            (ExpenseEntries.transactionDate lessEq endDate)
                }
                .map { row -> row.toExpenseEntry() }
        }
    }

    fun currentForPeriod(periodId: Int): List<ExpenseEntry> {
        return transaction {
            ExpenseEntries
                .selectAll()
                .where {
                    (ExpenseEntries.periodId eq periodId) and
                            ExpenseEntries.supersededAt.isNull()
                }
                .map { row -> row.toExpenseEntry() }
        }
    }

    fun currentForPeriodAndProperty(periodId: Int, propertyId: Int): List<ExpenseEntry> {
        return transaction {
            ExpenseEntries
                .selectAll()
                .where {
                    (ExpenseEntries.periodId eq periodId) and
                            (ExpenseEntries.propertyId eq propertyId) and
                            ExpenseEntries.supersededAt.isNull()
                }
                .map { row -> row.toExpenseEntry() }
        }
    }

    fun historyForPeriod(periodId: Int): List<ExpenseEntry> {
        return transaction {
            ExpenseEntries
                .selectAll()
                .where { ExpenseEntries.periodId eq periodId }
                .orderBy(ExpenseEntries.recordedAt, SortOrder.ASC)
                .map { row -> row.toExpenseEntry() }
        }
    }

    private fun ResultRow.toExpenseEntry() = ExpenseEntry(
        id              = this[ExpenseEntries.id],
        periodId        = this[ExpenseEntries.periodId],
        userId          = this[ExpenseEntries.userId],
        propertyId      = this[ExpenseEntries.propertyId],
        category        = this[ExpenseEntries.category],
        amount          = this[ExpenseEntries.amount],
        description     = this[ExpenseEntries.description],
        transactionDate = this[ExpenseEntries.transactionDate],
        recordedAt      = this[ExpenseEntries.recordedAt],
        supersededAt    = this[ExpenseEntries.supersededAt],
    )
}