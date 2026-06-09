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

import org.charlesatkinson.libremtd.database.tables.IncomePropertyEntries
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class IncomePropertyEntry(
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

object IncomePropertyRepository {

    fun recordPropertyIncome(
        periodId: Int,
        userId: Int,
        propertyId: Int,
        category: String,
        amount: Double,
        description: String,
        transactionDate: String,
    ): IncomePropertyEntry {
        return transaction {
            val now = LocalDateTime.now().toString()
            val id = IncomePropertyEntries.insert {
                it[IncomePropertyEntries.periodId]        = periodId
                it[IncomePropertyEntries.userId]          = userId
                it[IncomePropertyEntries.propertyId]      = propertyId
                it[IncomePropertyEntries.category]        = category
                it[IncomePropertyEntries.amount]          = amount
                it[IncomePropertyEntries.description]     = description
                it[IncomePropertyEntries.transactionDate] = transactionDate
                it[IncomePropertyEntries.recordedAt]      = now
                it[IncomePropertyEntries.supersededAt]    = null
            } get IncomePropertyEntries.id

            IncomePropertyEntry(
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
    ): IncomePropertyEntry {
        return transaction {
            val now = LocalDateTime.now().toString()

            IncomePropertyEntries.update({ IncomePropertyEntries.id eq existingId }) {
                it[IncomePropertyEntries.supersededAt] = now
            }

            val id = IncomePropertyEntries.insert {
                it[IncomePropertyEntries.periodId]        = periodId
                it[IncomePropertyEntries.userId]          = userId
                it[IncomePropertyEntries.propertyId]      = propertyId
                it[IncomePropertyEntries.category]        = category
                it[IncomePropertyEntries.amount]          = amount
                it[IncomePropertyEntries.description]     = description
                it[IncomePropertyEntries.transactionDate] = transactionDate
                it[IncomePropertyEntries.recordedAt]      = now
                it[IncomePropertyEntries.supersededAt]    = null
            } get IncomePropertyEntries.id

            IncomePropertyEntry(
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
            IncomePropertyEntries.update({ IncomePropertyEntries.id eq id }) {
                it[supersededAt] = LocalDateTime.now().toString()
            }
        }
    }

    fun currentPropertyIncomeForYear(userId: Int, taxYear: String): List<IncomePropertyEntry> {
        val (startDate, endDate) = taxYearDateRange(taxYear)
        return transaction {
            IncomePropertyEntries
                .selectAll()
                .where {
                    (IncomePropertyEntries.userId eq userId) and
                            (IncomePropertyEntries.supersededAt.isNull()) and
                            (IncomePropertyEntries.transactionDate greaterEq startDate) and
                            (IncomePropertyEntries.transactionDate lessEq endDate)
                }
                .map { row -> row.toIncomePropertyEntry() }
        }
    }

    fun currentForPeriod(periodId: Int): List<IncomePropertyEntry> {
        return transaction {
            IncomePropertyEntries
                .selectAll()
                .where {
                    (IncomePropertyEntries.periodId eq periodId) and
                            IncomePropertyEntries.supersededAt.isNull()
                }
                .map { row -> row.toIncomePropertyEntry() }
        }
    }

    fun currentForPeriodAndProperty(periodId: Int, propertyId: Int): List<IncomePropertyEntry> {
        return transaction {
            IncomePropertyEntries
                .selectAll()
                .where {
                    (IncomePropertyEntries.periodId eq periodId) and
                            (IncomePropertyEntries.propertyId eq propertyId) and
                            IncomePropertyEntries.supersededAt.isNull()
                }
                .map { row -> row.toIncomePropertyEntry() }
        }
    }

    fun historyForPeriod(periodId: Int): List<IncomePropertyEntry> {
        return transaction {
            IncomePropertyEntries
                .selectAll()
                .where { IncomePropertyEntries.periodId eq periodId }
                .orderBy(IncomePropertyEntries.recordedAt, SortOrder.ASC)
                .map { row -> row.toIncomePropertyEntry() }
        }
    }

    private fun ResultRow.toIncomePropertyEntry() = IncomePropertyEntry(
        id              = this[IncomePropertyEntries.id],
        periodId        = this[IncomePropertyEntries.periodId],
        userId          = this[IncomePropertyEntries.userId],
        propertyId      = this[IncomePropertyEntries.propertyId],
        category        = this[IncomePropertyEntries.category],
        amount          = this[IncomePropertyEntries.amount],
        description     = this[IncomePropertyEntries.description],
        transactionDate = this[IncomePropertyEntries.transactionDate],
        recordedAt      = this[IncomePropertyEntries.recordedAt],
        supersededAt    = this[IncomePropertyEntries.supersededAt],
    )
}