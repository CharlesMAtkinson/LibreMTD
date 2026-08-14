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

import org.charlesatkinson.libremtd.database.tables.IncomePropertyUkEntries
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class IncomePropertyUkEntry(
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

object IncomePropertyUkRepository {

    fun recordPropertyIncome(
        periodId: Int,
        userId: Int,
        propertyId: Int,
        category: String,
        amount: Double,
        description: String,
        transactionDate: String,
    ): IncomePropertyUkEntry {
        return transaction {
            val now = LocalDateTime.now().toString()
            val id = IncomePropertyUkEntries.insert {
                it[IncomePropertyUkEntries.periodId]        = periodId
                it[IncomePropertyUkEntries.userId]          = userId
                it[IncomePropertyUkEntries.propertyId]      = propertyId
                it[IncomePropertyUkEntries.category]        = category
                it[IncomePropertyUkEntries.amount]          = amount
                it[IncomePropertyUkEntries.description]     = description
                it[IncomePropertyUkEntries.transactionDate] = transactionDate
                it[IncomePropertyUkEntries.recordedAt]      = now
                it[IncomePropertyUkEntries.supersededAt]    = null
            } get IncomePropertyUkEntries.id

            IncomePropertyUkEntry(
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
    ): IncomePropertyUkEntry {
        return transaction {
            val now = LocalDateTime.now().toString()

            IncomePropertyUkEntries.update({ IncomePropertyUkEntries.id eq existingId }) {
                it[IncomePropertyUkEntries.supersededAt] = now
            }

            val id = IncomePropertyUkEntries.insert {
                it[IncomePropertyUkEntries.periodId]        = periodId
                it[IncomePropertyUkEntries.userId]          = userId
                it[IncomePropertyUkEntries.propertyId]      = propertyId
                it[IncomePropertyUkEntries.category]        = category
                it[IncomePropertyUkEntries.amount]          = amount
                it[IncomePropertyUkEntries.description]     = description
                it[IncomePropertyUkEntries.transactionDate] = transactionDate
                it[IncomePropertyUkEntries.recordedAt]      = now
                it[IncomePropertyUkEntries.supersededAt]    = null
            } get IncomePropertyUkEntries.id

            IncomePropertyUkEntry(
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
            IncomePropertyUkEntries.update({ IncomePropertyUkEntries.id eq id }) {
                it[supersededAt] = LocalDateTime.now().toString()
            }
        }
    }

    fun currentPropertyIncomeForYear(userId: Int, taxYear: String): List<IncomePropertyUkEntry> {
        val (startDate, endDate) = taxYearDateRange(taxYear)
        return transaction {
            IncomePropertyUkEntries
                .selectAll()
                .where {
                    (IncomePropertyUkEntries.userId eq userId) and
                            (IncomePropertyUkEntries.supersededAt.isNull()) and
                            (IncomePropertyUkEntries.transactionDate greaterEq startDate) and
                            (IncomePropertyUkEntries.transactionDate lessEq endDate)
                }
                .map { row -> row.toIncomePropertyUkEntry() }
        }
    }

    fun currentForPeriod(periodId: Int): List<IncomePropertyUkEntry> {
        return transaction {
            IncomePropertyUkEntries
                .selectAll()
                .where {
                    (IncomePropertyUkEntries.periodId eq periodId) and
                            IncomePropertyUkEntries.supersededAt.isNull()
                }
                .map { row -> row.toIncomePropertyUkEntry() }
        }
    }

    fun currentForPeriodAndProperty(periodId: Int, propertyId: Int): List<IncomePropertyUkEntry> {
        return transaction {
            IncomePropertyUkEntries
                .selectAll()
                .where {
                    (IncomePropertyUkEntries.periodId eq periodId) and
                            (IncomePropertyUkEntries.propertyId eq propertyId) and
                            IncomePropertyUkEntries.supersededAt.isNull()
                }
                .map { row -> row.toIncomePropertyUkEntry() }
        }
    }

    fun historyForPeriod(periodId: Int): List<IncomePropertyUkEntry> {
        return transaction {
            IncomePropertyUkEntries
                .selectAll()
                .where { IncomePropertyUkEntries.periodId eq periodId }
                .orderBy(IncomePropertyUkEntries.recordedAt, SortOrder.ASC)
                .map { row -> row.toIncomePropertyUkEntry() }
        }
    }

    private fun ResultRow.toIncomePropertyUkEntry() = IncomePropertyUkEntry(
        id              = this[IncomePropertyUkEntries.id],
        periodId        = this[IncomePropertyUkEntries.periodId],
        userId          = this[IncomePropertyUkEntries.userId],
        propertyId      = this[IncomePropertyUkEntries.propertyId],
        category        = this[IncomePropertyUkEntries.category],
        amount          = this[IncomePropertyUkEntries.amount],
        description     = this[IncomePropertyUkEntries.description],
        transactionDate = this[IncomePropertyUkEntries.transactionDate],
        recordedAt      = this[IncomePropertyUkEntries.recordedAt],
        supersededAt    = this[IncomePropertyUkEntries.supersededAt],
    )
}