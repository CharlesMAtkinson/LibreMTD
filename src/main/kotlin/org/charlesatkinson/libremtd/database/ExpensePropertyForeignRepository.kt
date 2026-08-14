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

import org.charlesatkinson.libremtd.database.tables.ExpensePropertyForeignEntries
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class ExpensePropertyForeignEntry(
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

object ExpensePropertyForeignRepository {

    fun recordForeignPropertyExpense(
        periodId: Int,
        userId: Int,
        propertyId: Int,
        category: String,
        amount: Double,
        description: String,
        transactionDate: String,
    ): ExpensePropertyForeignEntry {
        return transaction {
            val now = LocalDateTime.now().toString()
            val id = ExpensePropertyForeignEntries.insert {
                it[ExpensePropertyForeignEntries.periodId]        = periodId
                it[ExpensePropertyForeignEntries.userId]          = userId
                it[ExpensePropertyForeignEntries.propertyId]      = propertyId
                it[ExpensePropertyForeignEntries.category]        = category
                it[ExpensePropertyForeignEntries.amount]          = amount
                it[ExpensePropertyForeignEntries.description]     = description
                it[ExpensePropertyForeignEntries.transactionDate] = transactionDate
                it[ExpensePropertyForeignEntries.recordedAt]      = now
                it[ExpensePropertyForeignEntries.supersededAt]    = null
            } get ExpensePropertyForeignEntries.id

            ExpensePropertyForeignEntry(
                id              = id,
                periodId        = periodId,
                userId          = userId,
                propertyId      = propertyId,
                category        = category,
                amount          = amount,
                description     = description,
                transactionDate = transactionDate,
                recordedAt      = now,
            )
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
    ): ExpensePropertyForeignEntry {
        return transaction {
            val now = LocalDateTime.now().toString()

            ExpensePropertyForeignEntries.update({ ExpensePropertyForeignEntries.id eq existingId }) {
                it[ExpensePropertyForeignEntries.supersededAt] = now
            }

            val id = ExpensePropertyForeignEntries.insert {
                it[ExpensePropertyForeignEntries.periodId]        = periodId
                it[ExpensePropertyForeignEntries.userId]          = userId
                it[ExpensePropertyForeignEntries.propertyId]      = propertyId
                it[ExpensePropertyForeignEntries.category]        = category
                it[ExpensePropertyForeignEntries.amount]          = amount
                it[ExpensePropertyForeignEntries.description]     = description
                it[ExpensePropertyForeignEntries.transactionDate] = transactionDate
                it[ExpensePropertyForeignEntries.recordedAt]      = now
                it[ExpensePropertyForeignEntries.supersededAt]    = null
            } get ExpensePropertyForeignEntries.id

            ExpensePropertyForeignEntry(
                id              = id,
                periodId        = periodId,
                userId          = userId,
                propertyId      = propertyId,
                category        = category,
                amount          = amount,
                description     = description,
                transactionDate = transactionDate,
                recordedAt      = now,
            )
        }
    }

    fun delete(id: Int) {
        transaction {
            ExpensePropertyForeignEntries.update({ ExpensePropertyForeignEntries.id eq id }) {
                it[supersededAt] = LocalDateTime.now().toString()
            }
        }
    }

    fun currentForeignPropertyExpensesForYear(userId: Int, taxYear: String): List<ExpensePropertyForeignEntry> {
        val (startDate, endDate) = taxYearDateRange(taxYear)
        return transaction {
            ExpensePropertyForeignEntries
                .selectAll()
                .where {
                    (ExpensePropertyForeignEntries.userId eq userId) and
                            (ExpensePropertyForeignEntries.supersededAt.isNull()) and
                            (ExpensePropertyForeignEntries.transactionDate greaterEq startDate) and
                            (ExpensePropertyForeignEntries.transactionDate lessEq endDate)
                }
                .map { row -> row.toExpensePropertyForeignEntry() }
        }
    }

    fun currentForPeriod(periodId: Int): List<ExpensePropertyForeignEntry> {
        return transaction {
            ExpensePropertyForeignEntries
                .selectAll()
                .where {
                    (ExpensePropertyForeignEntries.periodId eq periodId) and
                            ExpensePropertyForeignEntries.supersededAt.isNull()
                }
                .map { row -> row.toExpensePropertyForeignEntry() }
        }
    }

    fun currentForPeriodAndProperty(periodId: Int, propertyId: Int): List<ExpensePropertyForeignEntry> {
        return transaction {
            ExpensePropertyForeignEntries
                .selectAll()
                .where {
                    (ExpensePropertyForeignEntries.periodId eq periodId) and
                            (ExpensePropertyForeignEntries.propertyId eq propertyId) and
                            ExpensePropertyForeignEntries.supersededAt.isNull()
                }
                .map { row -> row.toExpensePropertyForeignEntry() }
        }
    }

    fun historyForPeriod(periodId: Int): List<ExpensePropertyForeignEntry> {
        return transaction {
            ExpensePropertyForeignEntries
                .selectAll()
                .where { ExpensePropertyForeignEntries.periodId eq periodId }
                .orderBy(ExpensePropertyForeignEntries.recordedAt, SortOrder.ASC)
                .map { row -> row.toExpensePropertyForeignEntry() }
        }
    }

    private fun ResultRow.toExpensePropertyForeignEntry() = ExpensePropertyForeignEntry(
        id              = this[ExpensePropertyForeignEntries.id],
        periodId        = this[ExpensePropertyForeignEntries.periodId],
        userId          = this[ExpensePropertyForeignEntries.userId],
        propertyId      = this[ExpensePropertyForeignEntries.propertyId],
        category        = this[ExpensePropertyForeignEntries.category],
        amount          = this[ExpensePropertyForeignEntries.amount],
        description     = this[ExpensePropertyForeignEntries.description],
        transactionDate = this[ExpensePropertyForeignEntries.transactionDate],
        recordedAt      = this[ExpensePropertyForeignEntries.recordedAt],
        supersededAt    = this[ExpensePropertyForeignEntries.supersededAt],
    )
}
