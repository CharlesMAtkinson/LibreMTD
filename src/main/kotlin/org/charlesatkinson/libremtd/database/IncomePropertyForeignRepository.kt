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

import org.charlesatkinson.libremtd.database.tables.IncomePropertyForeignEntries
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class IncomePropertyForeignEntry(
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

object IncomePropertyForeignRepository {

    fun recordForeignPropertyIncome(
        periodId: Int,
        userId: Int,
        propertyId: Int,
        category: String,
        amount: Double,
        description: String,
        transactionDate: String,
    ): IncomePropertyForeignEntry {
        return transaction {
            val now = LocalDateTime.now().toString()
            val id = IncomePropertyForeignEntries.insert {
                it[IncomePropertyForeignEntries.periodId]        = periodId
                it[IncomePropertyForeignEntries.userId]          = userId
                it[IncomePropertyForeignEntries.propertyId]      = propertyId
                it[IncomePropertyForeignEntries.category]        = category
                it[IncomePropertyForeignEntries.amount]          = amount
                it[IncomePropertyForeignEntries.description]     = description
                it[IncomePropertyForeignEntries.transactionDate] = transactionDate
                it[IncomePropertyForeignEntries.recordedAt]      = now
                it[IncomePropertyForeignEntries.supersededAt]    = null
            } get IncomePropertyForeignEntries.id

            IncomePropertyForeignEntry(
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
    ): IncomePropertyForeignEntry {
        return transaction {
            val now = LocalDateTime.now().toString()

            IncomePropertyForeignEntries.update({ IncomePropertyForeignEntries.id eq existingId }) {
                it[IncomePropertyForeignEntries.supersededAt] = now
            }

            val id = IncomePropertyForeignEntries.insert {
                it[IncomePropertyForeignEntries.periodId]        = periodId
                it[IncomePropertyForeignEntries.userId]          = userId
                it[IncomePropertyForeignEntries.propertyId]      = propertyId
                it[IncomePropertyForeignEntries.category]        = category
                it[IncomePropertyForeignEntries.amount]          = amount
                it[IncomePropertyForeignEntries.description]     = description
                it[IncomePropertyForeignEntries.transactionDate] = transactionDate
                it[IncomePropertyForeignEntries.recordedAt]      = now
                it[IncomePropertyForeignEntries.supersededAt]    = null
            } get IncomePropertyForeignEntries.id

            IncomePropertyForeignEntry(
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
            IncomePropertyForeignEntries.update({ IncomePropertyForeignEntries.id eq id }) {
                it[supersededAt] = LocalDateTime.now().toString()
            }
        }
    }

    fun currentForeignPropertyIncomeForYear(userId: Int, taxYear: String): List<IncomePropertyForeignEntry> {
        val (startDate, endDate) = taxYearDateRange(taxYear)
        return transaction {
            IncomePropertyForeignEntries
                .selectAll()
                .where {
                    (IncomePropertyForeignEntries.userId eq userId) and
                            (IncomePropertyForeignEntries.supersededAt.isNull()) and
                            (IncomePropertyForeignEntries.transactionDate greaterEq startDate) and
                            (IncomePropertyForeignEntries.transactionDate lessEq endDate)
                }
                .map { row -> row.toIncomePropertyForeignEntry() }
        }
    }

    fun currentForPeriod(periodId: Int): List<IncomePropertyForeignEntry> {
        return transaction {
            IncomePropertyForeignEntries
                .selectAll()
                .where {
                    (IncomePropertyForeignEntries.periodId eq periodId) and
                            IncomePropertyForeignEntries.supersededAt.isNull()
                }
                .map { row -> row.toIncomePropertyForeignEntry() }
        }
    }

    fun currentForPeriodAndProperty(periodId: Int, propertyId: Int): List<IncomePropertyForeignEntry> {
        return transaction {
            IncomePropertyForeignEntries
                .selectAll()
                .where {
                    (IncomePropertyForeignEntries.periodId eq periodId) and
                            (IncomePropertyForeignEntries.propertyId eq propertyId) and
                            IncomePropertyForeignEntries.supersededAt.isNull()
                }
                .map { row -> row.toIncomePropertyForeignEntry() }
        }
    }

    fun historyForPeriod(periodId: Int): List<IncomePropertyForeignEntry> {
        return transaction {
            IncomePropertyForeignEntries
                .selectAll()
                .where { IncomePropertyForeignEntries.periodId eq periodId }
                .orderBy(IncomePropertyForeignEntries.recordedAt, SortOrder.ASC)
                .map { row -> row.toIncomePropertyForeignEntry() }
        }
    }

    private fun ResultRow.toIncomePropertyForeignEntry() = IncomePropertyForeignEntry(
        id              = this[IncomePropertyForeignEntries.id],
        periodId        = this[IncomePropertyForeignEntries.periodId],
        userId          = this[IncomePropertyForeignEntries.userId],
        propertyId      = this[IncomePropertyForeignEntries.propertyId],
        category        = this[IncomePropertyForeignEntries.category],
        amount          = this[IncomePropertyForeignEntries.amount],
        description     = this[IncomePropertyForeignEntries.description],
        transactionDate = this[IncomePropertyForeignEntries.transactionDate],
        recordedAt      = this[IncomePropertyForeignEntries.recordedAt],
        supersededAt    = this[IncomePropertyForeignEntries.supersededAt],
    )
}