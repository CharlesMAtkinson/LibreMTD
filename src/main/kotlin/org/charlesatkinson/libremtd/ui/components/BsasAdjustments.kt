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

package org.charlesatkinson.libremtd.ui.components

import org.charlesatkinson.libremtd.api.models.BsasAdjustmentsExpenses
import org.charlesatkinson.libremtd.api.models.BsasAdjustmentsIncome
import org.charlesatkinson.libremtd.api.models.BsasPostExpenses
import org.charlesatkinson.libremtd.api.models.BsasPostIncome
import org.charlesatkinson.libremtd.api.models.BsasPostPayload
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasExpenses
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasIncome
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasPostPayload
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasPostProperty
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasPropertyAdjustment
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasPropertyCalculation
import org.charlesatkinson.libremtd.api.models.ForeignPropertyBsasResponse
import org.charlesatkinson.libremtd.database.Property
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * The logic behind the final declaration's adjustment screens, kept apart
 * from the JavaFX code so that it can be unit tested. FinalDeclarationSection
 * builds the text fields and shows them; everything which decides what is
 * sent to HMRC lives here.
 */

/**
 * What to do with a field the user has cleared when it held a value from an
 * earlier adjustment: true sends 0, false leaves the field out.
 *
 * Sending 0 does not work. HMRC rejects it with FORMAT_VALUE, "The value
 * must be between -99999999999.99 and 99999999999.99 (but cannot be 0 or
 * 0.00)": confirmed in the HMRC sandbox on 31 October 2026. So this is
 * false, and a typed 0 is rejected by [resolveAdjustment] before it is sent.
 *
 * HMRC's guide says each new adjustment overwrites the previous one, so a
 * field which is left out means no adjustment for it.
 */
const val SEND_ZERO_FOR_CLEARED_FIELD = false

/** HMRC's limit on an adjustment, in either direction. */
private val MAX_ADJUSTMENT = BigDecimal("99999999999.99")

/** One adjustment field: [key] is HMRC's JSON field name, [label] is shown to the user. */
data class AdjustmentField(val key: String, val label: String)

val UK_INCOME_ADJUSTMENT_FIELDS = listOf(
    AdjustmentField("totalRentsReceived",   "Total rents received"),
    AdjustmentField("premiumsOfLeaseGrant", "Premiums of lease grant"),
    AdjustmentField("reversePremiums",      "Reverse premiums"),
    AdjustmentField("otherPropertyIncome",  "Other property income"),
)

val FOREIGN_INCOME_ADJUSTMENT_FIELDS = listOf(
    AdjustmentField("totalRentsReceived",   "Total rents received"),
    AdjustmentField("premiumsOfLeaseGrant", "Premiums of lease grant"),
    AdjustmentField("otherPropertyIncome",  "Other property income"),
)

/**
 * UK and foreign property have the same expense fields. Residential
 * financial cost is deliberately absent: HMRC rejects it for 2026-27
 * onwards.
 */
val ADJUSTMENT_EXPENSE_FIELDS = listOf(
    AdjustmentField("premisesRunningCosts",  "Premises running costs"),
    AdjustmentField("repairsAndMaintenance", "Repairs and maintenance"),
    AdjustmentField("financialCosts",        "Financial costs"),
    AdjustmentField("professionalFees",      "Professional fees"),
    AdjustmentField("costOfServices",        "Cost of services"),
    AdjustmentField("travelCosts",           "Travel costs"),
    AdjustmentField("other",                 "Other expenses"),
)

// ── Hint text ─────────────────────────────────────────────────────────────────

/**
 * The explanation shown above the adjustment fields for [propertyType]
 * ("UK property" or "foreign property"). Here rather than in the section so
 * a test can check that it describes what LibreMTD really does: a cleared
 * field is left out, not sent as zero, and a new submission replaces any
 * earlier one.
 */
fun bsasHintText(propertyType: String): String = normalizeText(
    """
        The summary has figures HMRC has calculated from your $propertyType submissions.
        {NL}{NL}
        The income and expense adjustment fields are what HMRC call Business Source
        Adjustable Summary (BSAS) adjustments. You can use them to correct your submitted
        figures at year end.
        {NL}{NL}
        No definitive list of possible adjustments was found. Examples include
        separating the revenue and capital portions of a maintenance invoice and
        correcting an income or expense item which was wrongly entered before the
        last quarterly submission. If you make any adjustments you should keep an
        itemised breakdown in case HMRC question them.
        {NL}{NL}
        Adjustments are changes, not actual amounts. For example, if you submitted
        £250 but the correct figure is £200, enter -50. Use at most two decimal places.
        HMRC does not accept an adjustment of 0.
        {NL}{NL}
        A new submission replaces any earlier adjustment HMRC holds for this tax year.
        HMRC keeps only the latest, so a field left blank means no adjustment for that
        field. If a field already has a figure, it is from an earlier submission: edit
        it to change it, or clear it to remove that adjustment.
        {NL}{NL}
        If you add or edit any adjustments, click "Submit adjustments", otherwise
        click "No adjustments".
    """
)

// ── One field ─────────────────────────────────────────────────────────────────

/** The text a field held when the screen was built, and what it holds now. */
data class AdjustmentInput(val initial: String, val current: String)

sealed class AdjustmentValue {
    /** Leave the field out of the request. */
    object Omit : AdjustmentValue()

    /** Send this amount. */
    data class Send(val amount: Double) : AdjustmentValue()

    /** The text is not an acceptable amount. */
    object Invalid : AdjustmentValue()
}

/** True if [text] is a number which equals zero, such as "0", "0.00" or "-0". */
internal fun isTypedZero(text: String): Boolean =
    text.trim().replace(",", "").toBigDecimalOrNull()?.signum() == 0

/**
 * Decides what to send for one field. A blank field is omitted, whether or
 * not it held a value (unless [sendZeroForCleared] says to send 0 for a
 * cleared one: see [SEND_ZERO_FOR_CLEARED_FIELD]). Typed text must be a
 * non-zero number with at most two decimal places and within HMRC's limit;
 * thousands separators are ignored.
 */
fun resolveAdjustment(
    input: AdjustmentInput,
    sendZeroForCleared: Boolean = SEND_ZERO_FOR_CLEARED_FIELD,
): AdjustmentValue {
    val text = input.current.trim()
    if (text.isEmpty()) {
        return if (input.initial.isNotBlank() && sendZeroForCleared) AdjustmentValue.Send(0.0)
        else AdjustmentValue.Omit
    }

    val decimal = text.replace(",", "").toBigDecimalOrNull() ?: return AdjustmentValue.Invalid
    if (decimal.signum() == 0) return AdjustmentValue.Invalid
    if (decimal.stripTrailingZeros().scale() > 2) return AdjustmentValue.Invalid
    if (decimal.abs() > MAX_ADJUSTMENT) return AdjustmentValue.Invalid
    return AdjustmentValue.Send(decimal.toDouble())
}

/** The text to put in a field for an existing value: two decimal places, or blank for none. */
fun formatAdjustment(value: Double?): String =
    if (value == null) "" else BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString()

// ── Whole payloads ────────────────────────────────────────────────────────────

sealed class BuiltPayload<out T> {
    data class Ready<T>(val payload: T) : BuiltPayload<T>()

    /**
     * [badFields] names every field with unacceptable text, for tests and
     * logging. [zeroFields] are those of them which were a typed zero, so
     * the screen can explain that case specifically.
     */
    data class Invalid(
        val badFields:  List<String>,
        val zeroFields: List<String> = emptyList(),
    ) : BuiltPayload<Nothing>()
}

/**
 * The message for the screen when a payload could not be built. A typed zero
 * gets its own sentence, because the usual advice (check the number of
 * decimal places) would not help the user.
 */
fun invalidAdjustmentMessage(invalid: BuiltPayload.Invalid): String {
    val parts = mutableListOf<String>()
    if (invalid.zeroFields.isNotEmpty()) {
        parts += "HMRC does not accept an adjustment of zero. To remove an adjustment, " +
                "clear the field: a blank field means no adjustment."
    }
    if (invalid.badFields.size > invalid.zeroFields.size) {
        parts += "One or more fields contain invalid numbers (at most two decimal places, " +
                "and no more than 99999999999.99 either way)."
    }
    return parts.joinToString("  ")
}

private fun resolveAll(
    inputs: Map<String, AdjustmentInput>,
    prefix: String,
    sendZeroForCleared: Boolean,
    bad: MutableList<String>,
    zero: MutableList<String>,
    badPrefix: String = "",
): Map<String, Double> {
    val sent = mutableMapOf<String, Double>()
    for ((key, input) in inputs) {
        when (val value = resolveAdjustment(input, sendZeroForCleared)) {
            is AdjustmentValue.Send    -> sent[key] = value.amount
            is AdjustmentValue.Invalid -> {
                bad += "$badPrefix$prefix$key"
                if (isTypedZero(input.current)) zero += "$badPrefix$prefix$key"
            }
            is AdjustmentValue.Omit    -> Unit
        }
    }
    return sent
}

private fun ukIncomeOf(sent: Map<String, Double>) = BsasPostIncome(
    totalRentsReceived   = sent["totalRentsReceived"],
    premiumsOfLeaseGrant = sent["premiumsOfLeaseGrant"],
    reversePremiums      = sent["reversePremiums"],
    otherPropertyIncome  = sent["otherPropertyIncome"],
)

private fun ukExpensesOf(sent: Map<String, Double>) = BsasPostExpenses(
    premisesRunningCosts  = sent["premisesRunningCosts"],
    repairsAndMaintenance = sent["repairsAndMaintenance"],
    financialCosts        = sent["financialCosts"],
    professionalFees      = sent["professionalFees"],
    costOfServices        = sent["costOfServices"],
    other                 = sent["other"],
    travelCosts           = sent["travelCosts"],
)

private fun foreignIncomeOf(sent: Map<String, Double>) = ForeignPropertyBsasIncome(
    totalRentsReceived   = sent["totalRentsReceived"],
    premiumsOfLeaseGrant = sent["premiumsOfLeaseGrant"],
    otherPropertyIncome  = sent["otherPropertyIncome"],
)

private fun foreignExpensesOf(sent: Map<String, Double>) = ForeignPropertyBsasExpenses(
    premisesRunningCosts  = sent["premisesRunningCosts"],
    repairsAndMaintenance = sent["repairsAndMaintenance"],
    financialCosts        = sent["financialCosts"],
    professionalFees      = sent["professionalFees"],
    costOfServices        = sent["costOfServices"],
    travelCosts           = sent["travelCosts"],
    other                 = sent["other"],
)

/**
 * The UK property payload from the income and expense inputs, each keyed by
 * HMRC's field name. The income or expenses part is null if nothing in it
 * is to be sent, so a payload with nothing to send has both null.
 */
fun buildUkPostPayload(
    income: Map<String, AdjustmentInput>,
    expenses: Map<String, AdjustmentInput>,
    sendZeroForCleared: Boolean = SEND_ZERO_FOR_CLEARED_FIELD,
): BuiltPayload<BsasPostPayload> {
    val bad = mutableListOf<String>()
    val zero = mutableListOf<String>()
    val sentIncome = resolveAll(income, "income.", sendZeroForCleared, bad, zero)
    val sentExpenses = resolveAll(expenses, "expenses.", sendZeroForCleared, bad, zero)
    if (bad.isNotEmpty()) return BuiltPayload.Invalid(bad, zero)

    return BuiltPayload.Ready(
        BsasPostPayload.Adjustments(
            income   = if (sentIncome.isEmpty()) null else ukIncomeOf(sentIncome),
            expenses = if (sentExpenses.isEmpty()) null else ukExpensesOf(sentExpenses),
        )
    )
}

/** The inputs for one foreign property's block on the review screen. */
data class PropertyAdjustmentInputs(
    val propertyId: String,
    val income: Map<String, AdjustmentInput>,
    val expenses: Map<String, AdjustmentInput>,
)

/**
 * The foreign property payload: one entry per property which has something
 * to send. A property with nothing to send is left out, so the payload is
 * empty if the user changed nothing.
 */
fun buildForeignPostPayload(
    properties: List<PropertyAdjustmentInputs>,
    sendZeroForCleared: Boolean = SEND_ZERO_FOR_CLEARED_FIELD,
): BuiltPayload<ForeignPropertyBsasPostPayload> {
    val bad = mutableListOf<String>()
    val zero = mutableListOf<String>()
    val entries = properties.mapNotNull { property ->
        val label = "${property.propertyId}: "
        val sentIncome = resolveAll(property.income, "income.", sendZeroForCleared, bad, zero, label)
        val sentExpenses = resolveAll(property.expenses, "expenses.", sendZeroForCleared, bad, zero, label)
        if (sentIncome.isEmpty() && sentExpenses.isEmpty()) null
        else ForeignPropertyBsasPostProperty(
            propertyId = property.propertyId,
            income     = if (sentIncome.isEmpty()) null else foreignIncomeOf(sentIncome),
            expenses   = if (sentExpenses.isEmpty()) null else foreignExpensesOf(sentExpenses),
        )
    }
    if (bad.isNotEmpty()) return BuiltPayload.Invalid(bad, zero)
    return BuiltPayload.Ready(ForeignPropertyBsasPostPayload(entries))
}

// ── Existing values, as maps keyed by HMRC field name ─────────────────────────

fun BsasAdjustmentsIncome?.toFieldMap(): Map<String, Double?> = mapOf(
    "totalRentsReceived"   to this?.totalRentsReceived,
    "premiumsOfLeaseGrant" to this?.premiumsOfLeaseGrant,
    "reversePremiums"      to this?.reversePremiums,
    "otherPropertyIncome"  to this?.otherPropertyIncome,
)

fun BsasAdjustmentsExpenses?.toFieldMap(): Map<String, Double?> = mapOf(
    "premisesRunningCosts"  to this?.premisesRunningCosts,
    "repairsAndMaintenance" to this?.repairsAndMaintenance,
    "financialCosts"        to this?.financialCosts,
    "professionalFees"      to this?.professionalFees,
    "costOfServices"        to this?.costOfServices,
    "travelCosts"           to this?.travelCosts,
    "other"                 to this?.other,
)

fun ForeignPropertyBsasIncome?.toFieldMap(): Map<String, Double?> = mapOf(
    "totalRentsReceived"   to this?.totalRentsReceived,
    "premiumsOfLeaseGrant" to this?.premiumsOfLeaseGrant,
    "otherPropertyIncome"  to this?.otherPropertyIncome,
)

fun ForeignPropertyBsasExpenses?.toFieldMap(): Map<String, Double?> = mapOf(
    "premisesRunningCosts"  to this?.premisesRunningCosts,
    "repairsAndMaintenance" to this?.repairsAndMaintenance,
    "financialCosts"        to this?.financialCosts,
    "professionalFees"      to this?.professionalFees,
    "costOfServices"        to this?.costOfServices,
    "travelCosts"           to this?.travelCosts,
    "other"                 to this?.other,
)

// ── Foreign property blocks ───────────────────────────────────────────────────

/**
 * Everything the review screen needs to show one foreign property.
 *
 * [countryCode] is what HMRC reported, or the local property's country if
 * HMRC reported an adjustment for a property it gave no summary for, or null.
 * [matchedLocalProperty] is false when no local property has this HMRC
 * property id; [label] then shows the raw id, so the property is never hidden.
 * [summary] is null for a property which has an adjustment but no summary.
 */
data class ForeignBsasPropertyBlock(
    val propertyId: String,
    val countryCode: String?,
    val label: String,
    val matchedLocalProperty: Boolean,
    val summary: ForeignPropertyBsasPropertyCalculation?,
    val existingIncome: Map<String, Double?>,
    val existingExpenses: Map<String, Double?>,
)

/**
 * One block per property HMRC reports, in the order HMRC reports them
 * (country by country), followed by any property which has an existing
 * adjustment but no summary. A property is never merged with another, even
 * in the same country.
 */
fun buildForeignBsasBlocks(
    response: ForeignPropertyBsasResponse,
    localProperties: List<Property>,
): List<ForeignBsasPropertyBlock> {
    val adjustmentsById: Map<String, ForeignPropertyBsasPropertyAdjustment> =
        response.adjustments?.propertyLevelDetail.orEmpty().associateBy { it.propertyId }

    fun block(
        propertyId: String,
        reportedCountry: String?,
        summary: ForeignPropertyBsasPropertyCalculation?,
    ): ForeignBsasPropertyBlock {
        val local = localProperties.firstOrNull { it.hmrcPropertyId == propertyId }
        val adjustment = adjustmentsById[propertyId]
        return ForeignBsasPropertyBlock(
            propertyId           = propertyId,
            countryCode          = reportedCountry ?: local?.countryCode,
            label                = if (local != null) propertySelectorLabel(local)
            else "Unrecognised property (HMRC property ID $propertyId)",
            matchedLocalProperty = local != null,
            summary              = summary,
            existingIncome       = adjustment?.income.toFieldMap(),
            existingExpenses     = adjustment?.expenses.toFieldMap(),
        )
    }

    val blocks = mutableListOf<ForeignBsasPropertyBlock>()
    val seen = mutableSetOf<String>()

    for (country in response.adjustableSummaryCalculation?.countryLevelDetail.orEmpty()) {
        for (property in country.propertyLevelDetail.orEmpty()) {
            if (seen.add(property.propertyId)) {
                blocks += block(property.propertyId, country.countryCode, property)
            }
        }
    }
    for (propertyId in adjustmentsById.keys) {
        if (seen.add(propertyId)) blocks += block(propertyId, null, null)
    }
    return blocks
}

// ── Profit or loss ────────────────────────────────────────────────────────────

/** A figure with the label which suits it. [amount] is null when HMRC gave none. */
data class LabelledFigure(val label: String, val amount: Double?)

/** "Net profit" when HMRC gave a profit, "Net loss" when it gave only a loss. */
fun netResult(netProfit: Double?, netLoss: Double?): LabelledFigure = when {
    netProfit != null -> LabelledFigure("Net profit", netProfit)
    netLoss != null   -> LabelledFigure("Net loss", netLoss)
    else              -> LabelledFigure("Net profit", null)
}

/** "Taxable profit" when HMRC gave one, "Adjusted income tax loss" when it gave only a loss. */
fun taxResult(taxableProfit: Double?, adjustedIncomeTaxLoss: Double?): LabelledFigure = when {
    taxableProfit != null         -> LabelledFigure("Taxable profit", taxableProfit)
    adjustedIncomeTaxLoss != null -> LabelledFigure("Adjusted income tax loss", adjustedIncomeTaxLoss)
    else                          -> LabelledFigure("Taxable profit", null)
}
