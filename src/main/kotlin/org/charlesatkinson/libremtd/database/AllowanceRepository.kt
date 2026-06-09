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

import org.charlesatkinson.libremtd.database.tables.AllowanceEntries
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class AllowanceEntry(
    val id: Int,
    val userId: Int,
    val taxYear: String,
    val category: String,
    val amount: Double,
    val recordedAt: String,
    val supersededAt: String? = null,
)

object AllowanceRepository {

    fun record(
        userId: Int,
        taxYear: String,
        category: String,
        amount: Double,
    ): AllowanceEntry {
        return transaction {
            val now = LocalDateTime.now().toString()
            val id = AllowanceEntries.insert {
                it[AllowanceEntries.userId]    = userId
                it[AllowanceEntries.taxYear]   = taxYear
                it[AllowanceEntries.category]  = category
                it[AllowanceEntries.amount]    = amount
                it[AllowanceEntries.recordedAt] = now
                it[AllowanceEntries.supersededAt] = null
            } get AllowanceEntries.id

            AllowanceEntry(id, userId, taxYear, category, amount, now)
        }
    }

    fun edit(
        existingId: Int,
        userId: Int,
        taxYear: String,
        category: String,
        amount: Double,
    ): AllowanceEntry {
        return transaction {
            val now = LocalDateTime.now().toString()

            AllowanceEntries.update({ AllowanceEntries.id eq existingId }) {
                it[AllowanceEntries.supersededAt] = now
            }

            val id = AllowanceEntries.insert {
                it[AllowanceEntries.userId]       = userId
                it[AllowanceEntries.taxYear]      = taxYear
                it[AllowanceEntries.category]     = category
                it[AllowanceEntries.amount]       = amount
                it[AllowanceEntries.recordedAt]   = now
                it[AllowanceEntries.supersededAt] = null
            } get AllowanceEntries.id

            AllowanceEntry(id, userId, taxYear, category, amount, now)
        }
    }

    fun currentForYear(userId: Int, taxYear: String): List<AllowanceEntry> {
        return transaction {
            AllowanceEntries
                .selectAll()
                .where {
                    (AllowanceEntries.userId eq userId) and
                            (AllowanceEntries.taxYear eq taxYear) and
                            (AllowanceEntries.supersededAt eq null)
                }
                .map { row -> row.toAllowanceEntry() }
        }
    }

    fun historyForYear(userId: Int, taxYear: String): List<AllowanceEntry> {
        return transaction {
            AllowanceEntries
                .selectAll()
                .where {
                    (AllowanceEntries.userId eq userId) and
                            (AllowanceEntries.taxYear eq taxYear)
                }
                .orderBy(AllowanceEntries.recordedAt, SortOrder.ASC)
                .map { row -> row.toAllowanceEntry() }
        }
    }

    private fun ResultRow.toAllowanceEntry() = AllowanceEntry(
        id           = this[AllowanceEntries.id],
        userId       = this[AllowanceEntries.userId],
        taxYear      = this[AllowanceEntries.taxYear],
        category     = this[AllowanceEntries.category],
        amount       = this[AllowanceEntries.amount],
        recordedAt   = this[AllowanceEntries.recordedAt],
        supersededAt = this[AllowanceEntries.supersededAt],
    )
}