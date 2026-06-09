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

package org.charlesatkinson.libremtd.database.tables

import org.jetbrains.exposed.sql.Table

/**
 * Stores foreign dividend entries for HMRC's general dividends-income endpoint.
 * Covers two HMRC categories: foreignDividend and dividendIncomeReceivedWhilstAbroad.
 * Each row is one country-level record within a tax year.
 */
object IncomeDividendForeignEntries : Table("income_dividend_foreign_entries") {
    val id                     = integer("id").autoIncrement()
    val userId                 = integer("user_id").references(Users.id)
    val taxYear                = text("tax_year")
    val category               = text("category")           // "ForeignDividend" | "DividendWhilstAbroad"
    val countryCode            = text("country_code")       // ISO 3166-1 alpha-3
    val amountBeforeTax        = double("amount_before_tax").nullable()
    val taxTakenOff            = double("tax_taken_off").nullable()
    val specialWithholdingTax  = double("special_withholding_tax").nullable()
    val foreignTaxCreditRelief = bool("foreign_tax_credit_relief")
    val taxableAmount          = double("taxable_amount")
    val recordedAt             = text("recorded_at")
    val supersededAt           = text("superseded_at").nullable()
    val transactionDate        = text("transaction_date")

    override val primaryKey = PrimaryKey(id)
}