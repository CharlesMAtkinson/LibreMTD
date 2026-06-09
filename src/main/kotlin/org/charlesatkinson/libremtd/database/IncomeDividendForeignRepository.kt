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

import org.charlesatkinson.libremtd.database.tables.IncomeDividendForeignEntries
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class IncomeDividendForeignEntry(
    val id: Int,
    val userId: Int,
    val taxYear: String,
    val category: String,
    val countryCode: String,
    val amountBeforeTax: Double?,
    val taxTakenOff: Double?,
    val specialWithholdingTax: Double?,
    val foreignTaxCreditRelief: Boolean,
    val taxableAmount: Double,
    val transactionDate: String,
    val recordedAt: String,
    val supersededAt: String? = null,
)

object IncomeDividendForeignRepository {

    fun currentEntriesForYear(userId: Int, taxYear: String): List<IncomeDividendForeignEntry> =
        transaction {
            IncomeDividendForeignEntries
                .selectAll()
                .where {
                    (IncomeDividendForeignEntries.userId eq userId) and
                            (IncomeDividendForeignEntries.taxYear eq taxYear) and
                            (IncomeDividendForeignEntries.supersededAt.isNull())
                }
                .map { it.toEntry() }
        }

    fun record(
        userId: Int,
        taxYear: String,
        category: String,
        countryCode: String,
        amountBeforeTax: Double?,
        taxTakenOff: Double?,
        specialWithholdingTax: Double?,
        foreignTaxCreditRelief: Boolean,
        taxableAmount: Double,
        transactionDate: String,
    ): IncomeDividendForeignEntry = transaction {
        val now = LocalDateTime.now().toString()
        val id = IncomeDividendForeignEntries.insert {
            it[IncomeDividendForeignEntries.userId]                 = userId
            it[IncomeDividendForeignEntries.taxYear]                = taxYear
            it[IncomeDividendForeignEntries.category]               = category
            it[IncomeDividendForeignEntries.countryCode]            = countryCode
            it[IncomeDividendForeignEntries.amountBeforeTax]        = amountBeforeTax
            it[IncomeDividendForeignEntries.taxTakenOff]            = taxTakenOff
            it[IncomeDividendForeignEntries.specialWithholdingTax]  = specialWithholdingTax
            it[IncomeDividendForeignEntries.foreignTaxCreditRelief] = foreignTaxCreditRelief
            it[IncomeDividendForeignEntries.taxableAmount]          = taxableAmount
            it[IncomeDividendForeignEntries.transactionDate]        = transactionDate
            it[IncomeDividendForeignEntries.recordedAt]             = now
            it[IncomeDividendForeignEntries.supersededAt]           = null
        } get IncomeDividendForeignEntries.id

        IncomeDividendForeignEntry(
            id                     = id,
            userId                 = userId,
            taxYear                = taxYear,
            category               = category,
            countryCode            = countryCode,
            amountBeforeTax        = amountBeforeTax,
            taxTakenOff            = taxTakenOff,
            specialWithholdingTax  = specialWithholdingTax,
            foreignTaxCreditRelief = foreignTaxCreditRelief,
            taxableAmount          = taxableAmount,
            transactionDate        = transactionDate,
            recordedAt             = now,
        )
    }

    fun delete(id: Int) = transaction {
        IncomeDividendForeignEntries.update({ IncomeDividendForeignEntries.id eq id }) {
            it[supersededAt] = LocalDateTime.now().toString()
        }
    }

    fun edit(
        existingId: Int,
        userId: Int,
        taxYear: String,
        category: String,
        countryCode: String,
        amountBeforeTax: Double?,
        taxTakenOff: Double?,
        specialWithholdingTax: Double?,
        foreignTaxCreditRelief: Boolean,
        taxableAmount: Double,
        transactionDate: String,
    ): IncomeDividendForeignEntry = transaction {
        val now = LocalDateTime.now().toString()
        IncomeDividendForeignEntries.update({ IncomeDividendForeignEntries.id eq existingId }) {
            it[supersededAt] = now
        }
        record(
            userId                 = userId,
            taxYear                = taxYear,
            category               = category,
            countryCode            = countryCode,
            amountBeforeTax        = amountBeforeTax,
            taxTakenOff            = taxTakenOff,
            specialWithholdingTax  = specialWithholdingTax,
            foreignTaxCreditRelief = foreignTaxCreditRelief,
            taxableAmount          = taxableAmount,
            transactionDate        = transactionDate,
        )
    }

    private fun ResultRow.toEntry() = IncomeDividendForeignEntry(
        id                     = this[IncomeDividendForeignEntries.id],
        userId                 = this[IncomeDividendForeignEntries.userId],
        taxYear                = this[IncomeDividendForeignEntries.taxYear],
        category               = this[IncomeDividendForeignEntries.category],
        countryCode            = this[IncomeDividendForeignEntries.countryCode],
        amountBeforeTax        = this[IncomeDividendForeignEntries.amountBeforeTax],
        taxTakenOff            = this[IncomeDividendForeignEntries.taxTakenOff],
        specialWithholdingTax  = this[IncomeDividendForeignEntries.specialWithholdingTax],
        foreignTaxCreditRelief = this[IncomeDividendForeignEntries.foreignTaxCreditRelief],
        taxableAmount          = this[IncomeDividendForeignEntries.taxableAmount],
        transactionDate        = this[IncomeDividendForeignEntries.transactionDate],
        recordedAt             = this[IncomeDividendForeignEntries.recordedAt],
        supersededAt           = this[IncomeDividendForeignEntries.supersededAt],
    )
}