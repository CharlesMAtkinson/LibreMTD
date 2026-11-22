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

package org.charlesatkinson.libremtd.api.models

import kotlinx.serialization.Serializable

@Serializable
data class BsasMetadata(
    val calculationId: String,
    val requestedDateTime: String? = null,
    val adjustedDateTime: String? = null,
    val nino: String,
    val taxYear: String,
    val summaryStatus: String? = null,
)

@Serializable
data class BsasIncomeBreakdown(
    val totalRentsReceived: Double? = null,
    val premiumsOfLeaseGrant: Double? = null,
    val reversePremiums: Double? = null,
    val otherPropertyIncome: Double? = null,
    val rarRentReceived: Double? = null,
)

@Serializable
data class BsasExpensesBreakdown(
    val premisesRunningCosts: Double? = null,
    val repairsAndMaintenance: Double? = null,
    val financialCosts: Double? = null,
    val professionalFees: Double? = null,
    val costOfServices: Double? = null,
    val travelCosts: Double? = null,
    // Read-only: HMRC has deprecated residentialFinancialCost for 2026-27
    // onwards (it is returned as 0), so it is never displayed or sent.
    val residentialFinancialCost: Double? = null,
    val broughtFwdResidentialFinancialCost: Double? = null,
    val other: Double? = null,
)

@Serializable
data class BsasAdditions(
    val privateUseAdjustment: Double? = null,
    val balancingCharge: Double? = null,
    val bpraBalancingCharge: Double? = null,
)

@Serializable
data class BsasDeductions(
    val zeroEmissionGoods: Double? = null,
    val annualInvestmentAllowance: Double? = null,
    val costOfReplacingDomesticItems: Double? = null,
    val businessPremisesRenovationAllowance: Double? = null,
    val otherCapitalAllowance: Double? = null,
    val rarReliefClaimed: Double? = null,
    val structuredBuildingAllowance: Double? = null,
    val enhancedStructuredBuildingAllowance: Double? = null,
    val zeroEmissionsCarAllowance: Double? = null,
)

/**
 * netLoss and adjustedIncomeTaxLoss are returned instead of netProfit and
 * taxableProfit when the result is a loss. They were seen in HMRC's foreign
 * property loss response; they are assumed to be the same for UK property,
 * which has not yet been confirmed against a UK loss response.
 */
@Serializable
data class BsasSummaryCalculation(
    val totalIncome: Double? = null,
    val income: BsasIncomeBreakdown? = null,
    val totalExpenses: Double? = null,
    val expenses: BsasExpensesBreakdown? = null,
    val netProfit: Double? = null,
    val netLoss: Double? = null,
    val totalAdditions: Double? = null,
    val additions: BsasAdditions? = null,
    val totalDeductions: Double? = null,
    val deductions: BsasDeductions? = null,
    val taxableProfit: Double? = null,
    val adjustedIncomeTaxLoss: Double? = null,
)

@Serializable
data class BsasAdjustmentsIncome(
    val totalRentsReceived: Double? = null,
    val premiumsOfLeaseGrant: Double? = null,
    val reversePremiums: Double? = null,
    val otherPropertyIncome: Double? = null,
)

// residentialFinancialCost is deliberately absent: HMRC rejects it for
// 2026-27 onwards (RULE_RESIDENTIAL_FINANCIAL_COST_NOT_ALLOWED).
@Serializable
data class BsasAdjustmentsExpenses(
    val premisesRunningCosts: Double? = null,
    val repairsAndMaintenance: Double? = null,
    val financialCosts: Double? = null,
    val professionalFees: Double? = null,
    val costOfServices: Double? = null,
    val other: Double? = null,
    val travelCosts: Double? = null,
)

@Serializable
data class BsasAdjustments(
    val zeroAdjustments: Boolean? = null,
    val income: BsasAdjustmentsIncome? = null,
    val expenses: BsasAdjustmentsExpenses? = null,
)

@Serializable
data class BsasInputs(
    val businessId: String? = null,
    val businessName: String? = null,
    val accountingPeriodStartDate: String? = null,
    val accountingPeriodEndDate: String? = null,
    val source: String? = null,
)

@Serializable
data class UkPropertyBsasResponse(
    val metadata: BsasMetadata,
    val inputs: BsasInputs? = null,
    val adjustableSummaryCalculation: BsasSummaryCalculation? = null,
    val adjustments: BsasAdjustments? = null,
    val adjustedSummaryCalculation: BsasSummaryCalculation? = null,
)

// ── UK property POST request models ───────────────────────────────────────────

@Serializable
data class BsasPostIncome(
    val totalRentsReceived: Double? = null,
    val premiumsOfLeaseGrant: Double? = null,
    val reversePremiums: Double? = null,
    val otherPropertyIncome: Double? = null,
)

@Serializable
data class BsasPostExpenses(
    val premisesRunningCosts: Double? = null,
    val repairsAndMaintenance: Double? = null,
    val financialCosts: Double? = null,
    val professionalFees: Double? = null,
    val costOfServices: Double? = null,
    val other: Double? = null,
    val travelCosts: Double? = null,
)

sealed class BsasPostPayload {
    object Zero : BsasPostPayload()
    data class Adjustments(
        val income: BsasPostIncome? = null,
        val expenses: BsasPostExpenses? = null,
    ) : BsasPostPayload()
}

// ── Foreign property BSAS: income and expenses ───────────────────────────────
//
// The same two classes are used for summary figures, existing adjustments
// and the POST body, because HMRC uses the same field names in all three.
// Consolidated expenses are not supported by LibreMTD, and
// residentialFinancialCost is rejected by HMRC for 2026-27 onwards, so
// neither is modelled.

@Serializable
data class ForeignPropertyBsasIncome(
    val totalRentsReceived:   Double? = null,
    val premiumsOfLeaseGrant: Double? = null,
    val otherPropertyIncome:  Double? = null,
)

@Serializable
data class ForeignPropertyBsasExpenses(
    val premisesRunningCosts:  Double? = null,
    val repairsAndMaintenance: Double? = null,
    val financialCosts:        Double? = null,
    val professionalFees:      Double? = null,
    val costOfServices:        Double? = null,
    val travelCosts:           Double? = null,
    val other:                 Double? = null,
)

// ── Foreign property BSAS GET response (tax years 2026-27 onwards) ───────────
//
// Shape confirmed from HMRC sandbox responses (FOREIGN_PROPERTY_PROFIT,
// FOREIGN_PROPERTY_LOSS and DYNAMIC_FOREIGN_PROPERTY_PROFIT):
//
//   adjustableSummaryCalculation
//     overall totals
//     countryLevelDetail[]         one per country, with country totals
//       propertyLevelDetail[]      one per property, keyed by propertyId
//   adjustments
//     propertyLevelDetail[]        one per property, keyed by propertyId
//
// A profit result has netProfit and taxableProfit; a loss result has
// netLoss and adjustedIncomeTaxLoss instead.

@Serializable
data class ForeignPropertyBsasPropertyCalculation(
    val propertyId:            String,
    val totalIncome:           Double? = null,
    val income:                ForeignPropertyBsasIncome? = null,
    val totalExpenses:         Double? = null,
    val expenses:              ForeignPropertyBsasExpenses? = null,
    val netProfit:             Double? = null,
    val netLoss:               Double? = null,
    val taxableProfit:         Double? = null,
    val adjustedIncomeTaxLoss: Double? = null,
)

@Serializable
data class ForeignPropertyBsasCountryCalculation(
    val countryCode:           String,
    val totalIncome:           Double? = null,
    val income:                ForeignPropertyBsasIncome? = null,
    val totalExpenses:         Double? = null,
    val expenses:              ForeignPropertyBsasExpenses? = null,
    val netProfit:             Double? = null,
    val netLoss:               Double? = null,
    val taxableProfit:         Double? = null,
    val adjustedIncomeTaxLoss: Double? = null,
    val propertyLevelDetail:   List<ForeignPropertyBsasPropertyCalculation>? = null,
)

@Serializable
data class ForeignPropertyBsasSummaryCalculation(
    val totalIncome:           Double? = null,
    val income:                ForeignPropertyBsasIncome? = null,
    val totalExpenses:         Double? = null,
    val expenses:              ForeignPropertyBsasExpenses? = null,
    val netProfit:             Double? = null,
    val netLoss:               Double? = null,
    val taxableProfit:         Double? = null,
    val adjustedIncomeTaxLoss: Double? = null,
    val countryLevelDetail:    List<ForeignPropertyBsasCountryCalculation>? = null,
)

@Serializable
data class ForeignPropertyBsasPropertyAdjustment(
    val propertyId: String,
    val income:     ForeignPropertyBsasIncome? = null,
    val expenses:   ForeignPropertyBsasExpenses? = null,
)

@Serializable
data class ForeignPropertyBsasAdjustments(
    val propertyLevelDetail: List<ForeignPropertyBsasPropertyAdjustment>? = null,
)

@Serializable
data class ForeignPropertyBsasResponse(
    val metadata:                     BsasMetadata,
    val inputs:                       BsasInputs? = null,
    val adjustableSummaryCalculation: ForeignPropertyBsasSummaryCalculation? = null,
    val adjustments:                  ForeignPropertyBsasAdjustments? = null,
)

// ── Foreign property BSAS POST payload ───────────────────────────────────────
//
// Sent as foreignProperty.propertyLevelDetail[]. See
// BsasClient.buildForeignPostJson.

data class ForeignPropertyBsasPostProperty(
    val propertyId: String,
    val income:     ForeignPropertyBsasIncome? = null,
    val expenses:   ForeignPropertyBsasExpenses? = null,
)

data class ForeignPropertyBsasPostPayload(
    val propertyLevelDetail: List<ForeignPropertyBsasPostProperty>,
)
