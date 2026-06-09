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

package org.charlesatkinson.libremtd.ui

// ---------------------------------------------------------------------------
// Shared category enums
//
// These are used by both the UI panes and the spreadsheet import/export layer.
// Keep all four enums here so there is one canonical definition of each.
// ---------------------------------------------------------------------------

/**
 * Income categories for UK property letting.
 * dbKey     — stored in the database category column
 * hmrcField — JSON field name in the HMRC v6.0 cumulative payload
 */
enum class IncomeCategory(val label: String, val dbKey: String, val hmrcField: String) {
    TotalRentReceived    ("Total rent received",     "TotalRentReceived",    "periodAmount"),
    TaxDeducted          ("Tax deducted at source",  "TaxDeducted",          "taxDeducted"),
    PremiumsOfLeaseGrant ("Premiums of lease grant", "PremiumsOfLeaseGrant", "premiumsOfLeaseGrant"),
    ReversePremiums      ("Reverse premiums",        "ReversePremiums",      "reversePremiums"),
    OtherIncome          ("Other income",            "OtherIncome",          "otherIncome"),
}

/**
 * Expense categories for UK property letting.
 * Extend hmrcField values to match the HMRC payload when that is implemented.
 */
enum class ExpenseCategory(val label: String, val dbKey: String) {
    PremisesRunningCosts              ("Premises running costs",                    "PremisesRunningCosts"),
    RepairsAndMaintenance             ("Repairs and maintenance",                   "RepairsAndMaintenance"),
    FinancialCosts                    ("Financial costs",                           "FinancialCosts"),
    ProfessionalFees                  ("Professional fees",                         "ProfessionalFees"),
    CostOfServices                    ("Cost of services",                          "CostOfServices"),
    ResidentialFinanceCost            ("Residential finance cost",                  "ResidentialFinanceCost"),
    ResidentialFinanceCostCarriedForward ("Residential finance cost carried forward", "ResidentialFinanceCostCarriedForward"),
    TravelCosts                       ("Travel costs",                              "TravelCosts"),
    OtherExpenses                     ("Other expenses",                            "OtherExpenses"),
}

/**
 * Income categories for UK dividends (HMRC UK dividends endpoint).
 */
enum class DividendCategory(val label: String, val dbKey: String) {
    UkDividends      ("UK dividends",       "UkDividends"),
    OtherUkDividends ("Dividends from UK funds (unit trusts / OEICs)", "OtherUkDividends"),
}

/**
 * Income categories for the HMRC general dividends-income endpoint —
 * single-object types (grossAmount + optional customerReference).
 */
enum class DividendScalarCategory(val label: String, val dbKey: String) {
    StockDividend            ("Stock dividend",              "StockDividend"),
    RedeemableShares         ("Redeemable shares",           "RedeemableShares"),
    BonusIssuesOfSecurities  ("Bonus issues of securities",  "BonusIssuesOfSecurities"),
    CloseCompanyLoansWrittenOff("Close company loans written off", "CloseCompanyLoansWrittenOff"),
}

/**
 * Income categories for the HMRC general dividends-income endpoint —
 * per-country array types. Each record is tied to one country code.
 */
enum class DividendForeignCategory(val label: String, val dbKey: String) {
    ForeignDividend   ("Foreign dividend",                  "ForeignDividend"),
    DividendWhilstAbroad("Dividend income received whilst abroad", "DividendWhilstAbroad"),
}

/**
 * Income categories for UK savings interest.
 */
enum class SavingsCategory(val label: String, val dbKey: String) {
    BankBuildingSociety   ("Bank / building society",  "BankBuildingSociety"),
    UkSecurities          ("UK securities",            "UkSecurities"),
    Other                 ("Other",                    "Other"),
}
