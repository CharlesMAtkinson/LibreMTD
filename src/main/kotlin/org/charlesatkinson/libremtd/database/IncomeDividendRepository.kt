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

import org.charlesatkinson.libremtd.database.tables.IncomeDividendEntries
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class IncomeDividendEntry(
    val id: Int,
    val userId: Int,
    val taxYear: String,
    val category: String,
    val amount: Double,
    val customerReference: String? = null,
    val description: String,
    val transactionDate: String,
    val recordedAt: String,
    val supersededAt: String? = null,
)

object IncomeDividendRepository {

    fun currentDividendsForYear(userId: Int, taxYear: String): List<IncomeDividendEntry> =
        transaction {
            IncomeDividendEntries
                .selectAll()
                .where {
                    (IncomeDividendEntries.userId eq userId) and
                            (IncomeDividendEntries.taxYear eq taxYear) and
                            (IncomeDividendEntries.supersededAt.isNull())
                }
                .map { it.toIncomeDividendEntry() }
        }

    fun delete(id: Int) = transaction {
        IncomeDividendEntries.update({ IncomeDividendEntries.id eq id }) {
            it[supersededAt] = LocalDateTime.now().toString()
        }
    }

    fun edit(
        existingId: Int,
        userId: Int,
        taxYear: String,
        category: String,
        amount: Double,
        customerReference: String? = null,
        description: String,
        transactionDate: String,
    ): IncomeDividendEntry = transaction {
        val now = LocalDateTime.now().toString()
        IncomeDividendEntries.update({ IncomeDividendEntries.id eq existingId }) {
            it[IncomeDividendEntries.supersededAt] = now
        }
        val id = IncomeDividendEntries.insert {
            it[IncomeDividendEntries.taxYear]           = taxYear
            it[IncomeDividendEntries.userId]            = userId
            it[IncomeDividendEntries.category]          = category
            it[IncomeDividendEntries.amount]            = amount
            it[IncomeDividendEntries.customerReference] = customerReference
            it[IncomeDividendEntries.description]       = description
            it[IncomeDividendEntries.transactionDate]   = transactionDate
            it[IncomeDividendEntries.recordedAt]        = now
            it[IncomeDividendEntries.supersededAt]      = null
        } get IncomeDividendEntries.id

        IncomeDividendEntry(id, userId, taxYear, category, amount, customerReference,
            description, transactionDate, now)
    }

    fun recordDividend(
        userId: Int,
        taxYear: String,
        category: String,
        amount: Double,
        customerReference: String? = null,
        description: String,
        transactionDate: String,
    ): IncomeDividendEntry = transaction {
        val now = LocalDateTime.now().toString()
        val id = IncomeDividendEntries.insert {
            it[IncomeDividendEntries.taxYear]           = taxYear
            it[IncomeDividendEntries.userId]            = userId
            it[IncomeDividendEntries.category]          = category
            it[IncomeDividendEntries.amount]            = amount
            it[IncomeDividendEntries.customerReference] = customerReference
            it[IncomeDividendEntries.description]       = description
            it[IncomeDividendEntries.transactionDate]   = transactionDate
            it[IncomeDividendEntries.recordedAt]        = now
            it[IncomeDividendEntries.supersededAt]      = null
        } get IncomeDividendEntries.id

        IncomeDividendEntry(id, userId, taxYear, category, amount, customerReference,
            description, transactionDate, now)
    }

    private fun ResultRow.toIncomeDividendEntry() = IncomeDividendEntry(
        id                = this[IncomeDividendEntries.id],
        userId            = this[IncomeDividendEntries.userId],
        taxYear           = this[IncomeDividendEntries.taxYear],
        category          = this[IncomeDividendEntries.category],
        amount            = this[IncomeDividendEntries.amount],
        customerReference = this[IncomeDividendEntries.customerReference],
        description       = this[IncomeDividendEntries.description],
        transactionDate   = this[IncomeDividendEntries.transactionDate],
        recordedAt        = this[IncomeDividendEntries.recordedAt],
        supersededAt      = this[IncomeDividendEntries.supersededAt],
    )
}