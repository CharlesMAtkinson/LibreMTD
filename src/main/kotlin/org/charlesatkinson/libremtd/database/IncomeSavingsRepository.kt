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

import org.charlesatkinson.libremtd.database.tables.IncomeSavingsEntries
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class IncomeSavingsEntry(
    val id: Int,
    val userId: Int,
    val taxYear: String,
    val category: String,
    val amount: Double,
    val description: String,
    val transactionDate: String,
    val recordedAt: String,
    val supersededAt: String? = null,
)

object IncomeSavingsRepository {

    fun recordSavingsIncome(
        userId: Int,
        taxYear: String,
        category: String,
        amount: Double,
        description: String,
        transactionDate: String,
    ): IncomeSavingsEntry {
        return transaction {
            val now = LocalDateTime.now().toString()
            val id = IncomeSavingsEntries.insert {
                it[IncomeSavingsEntries.userId]          = userId
                it[IncomeSavingsEntries.taxYear]         = taxYear
                it[IncomeSavingsEntries.category]        = category
                it[IncomeSavingsEntries.amount]          = amount
                it[IncomeSavingsEntries.description]     = description
                it[IncomeSavingsEntries.transactionDate] = transactionDate
                it[IncomeSavingsEntries.recordedAt]      = now
                it[IncomeSavingsEntries.supersededAt]    = null
            } get IncomeSavingsEntries.id

            IncomeSavingsEntry(
                id              = id,
                userId          = userId,
                taxYear         = taxYear,
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
        userId: Int,
        taxYear: String,
        category: String,
        amount: Double,
        description: String,
        transactionDate: String,
    ): IncomeSavingsEntry {
        return transaction {
            val now = LocalDateTime.now().toString()

            IncomeSavingsEntries.update({ IncomeSavingsEntries.id eq existingId }) {
                it[IncomeSavingsEntries.supersededAt] = now
            }

            val id = IncomeSavingsEntries.insert {
                it[IncomeSavingsEntries.userId]          = userId
                it[IncomeSavingsEntries.taxYear]         = taxYear
                it[IncomeSavingsEntries.category]        = category
                it[IncomeSavingsEntries.amount]          = amount
                it[IncomeSavingsEntries.description]     = description
                it[IncomeSavingsEntries.transactionDate] = transactionDate
                it[IncomeSavingsEntries.recordedAt]      = now
                it[IncomeSavingsEntries.supersededAt]    = null
            } get IncomeSavingsEntries.id

            IncomeSavingsEntry(
                id              = id,
                userId          = userId,
                taxYear         = taxYear,
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
            IncomeSavingsEntries.update({ IncomeSavingsEntries.id eq id }) {
                it[supersededAt] = LocalDateTime.now().toString()
            }
        }
    }

    fun currentForTaxYear(userId: Int, taxYear: String): List<IncomeSavingsEntry> {
        return transaction {
            IncomeSavingsEntries
                .selectAll()
                .where {
                    (IncomeSavingsEntries.userId eq userId) and
                            (IncomeSavingsEntries.taxYear eq taxYear) and
                            IncomeSavingsEntries.supersededAt.isNull()
                }
                .orderBy(IncomeSavingsEntries.transactionDate)
                .map { it.toIncomeSavingsEntry() }
        }
    }

    fun historyForTaxYear(userId: Int, taxYear: String): List<IncomeSavingsEntry> {
        return transaction {
            IncomeSavingsEntries
                .selectAll()
                .where {
                    (IncomeSavingsEntries.userId eq userId) and
                            (IncomeSavingsEntries.taxYear eq taxYear)
                }
                .orderBy(IncomeSavingsEntries.recordedAt, SortOrder.ASC)
                .map { it.toIncomeSavingsEntry() }
        }
    }

    private fun ResultRow.toIncomeSavingsEntry() = IncomeSavingsEntry(
        id              = this[IncomeSavingsEntries.id],
        userId          = this[IncomeSavingsEntries.userId],
        taxYear         = this[IncomeSavingsEntries.taxYear],
        category        = this[IncomeSavingsEntries.category],
        amount          = this[IncomeSavingsEntries.amount],
        description     = this[IncomeSavingsEntries.description],
        transactionDate = this[IncomeSavingsEntries.transactionDate],
        recordedAt      = this[IncomeSavingsEntries.recordedAt],
        supersededAt    = this[IncomeSavingsEntries.supersededAt],
    )
}