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

import org.charlesatkinson.libremtd.database.tables.ForeignPropertyElections
import org.charlesatkinson.libremtd.database.tables.Properties
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

enum class PropertyType { UK, FOREIGN }

data class Property(
    val id: Int,
    val userId: Int,
    val address: String,
    val postcode: String?,
    val propertyType: PropertyType,
    val countryCode: String?,
    val hmrcPropertyId: String?,
    val hmrcRegisteredAt: String?,
    val hmrcRegisteredTaxYear: String?,
    val createdAt: String,
    val endedAt: String?,
    val supersededAt: String?,
)

object PropertyRepository {

    fun create(
        userId: Int,
        address: String,
        propertyType: PropertyType,
        postcode: String? = null,
        countryCode: String? = null,
    ): Property {
        require(propertyType != PropertyType.UK || postcode != null) {
            "UK properties require a postcode"
        }
        require(propertyType != PropertyType.FOREIGN || countryCode != null) {
            "Foreign properties require a country code"
        }

        return transaction {
            val createdAt = LocalDateTime.now().toString()
            val id = Properties.insert {
                it[Properties.userId]                = userId
                it[Properties.address]               = address
                it[Properties.postcode]              = postcode
                it[Properties.propertyType]          = propertyType.name
                it[Properties.countryCode]           = countryCode
                it[Properties.hmrcPropertyId]        = null
                it[Properties.hmrcRegisteredAt]      = null
                it[Properties.hmrcRegisteredTaxYear] = null
                it[Properties.createdAt]             = createdAt
                it[Properties.endedAt]               = null
            } get Properties.id

            Property(
                id = id,
                userId = userId,
                address = address,
                postcode = postcode,
                propertyType = propertyType,
                countryCode = countryCode,
                hmrcPropertyId = null,
                hmrcRegisteredAt = null,
                hmrcRegisteredTaxYear = null,
                createdAt = createdAt,
                endedAt = null,
                supersededAt = null,
            )
        }
    }

    /**
     * Returns every property for [userId] that has not been removed —
     * including ended ones, so they remain available for review and for
     * amending entries in tax years not yet Final Declared. Callers that
     * want to distinguish active from ended should check [Property.endedAt].
     */
    fun findByUser(userId: Int): List<Property> {
        return transaction {
            Properties
                .selectAll()
                .where { (Properties.userId eq userId) and Properties.supersededAt.isNull() }
                .map { row -> row.toProperty() }
        }
    }

    /**
     * Returns a single property by its id, or null if it does not exist or
     * has been removed. Added so that code which only has a propertyId —
     * such as FinalDeclarationGuard's use from ForeignPropertyElectionRepository
     * — can resolve the owning userId without depending on findByUser.
     */
    fun findById(id: Int): Property? {
        return transaction {
            Properties
                .selectAll()
                .where { (Properties.id eq id) and Properties.supersededAt.isNull() }
                .map { row -> row.toProperty() }
                .singleOrNull()
        }
    }

    /**
     * Records that a foreign property has been registered with HMRC and
     * issued [hmrcPropertyId] — either at creation time (2026-27+ properties
     * added directly), or later via a separate "Register with HMRC" action
     * on a pre-existing local-only property (e.g. one added under 2025-26).
     *
     * [taxYear] is the tax year the registration was made under. It must be
     * kept because HMRC's Update/End Foreign Property Details endpoint is
     * keyed on it — even though registration itself is understood to carry
     * forward to later tax years without being repeated.
     */
    fun registerWithHmrc(id: Int, hmrcPropertyId: String, taxYear: String) {
        transaction {
            Properties.update({ Properties.id eq id }) {
                it[Properties.hmrcPropertyId]        = hmrcPropertyId
                it[Properties.hmrcRegisteredAt]      = LocalDateTime.now().toString()
                it[Properties.hmrcRegisteredTaxYear] = taxYear
            }
        }
    }

    /**
     * Marks a property's letting as ended. This is not deletion: the
     * property and all its existing income/expense entries remain in place
     * and remain returned by [findByUser], so they stay available for
     * review and for amendment up to Final Declaration.
     */
    fun end(id: Int) {
        transaction {
            Properties.update({ Properties.id eq id }) {
                it[endedAt] = LocalDateTime.now().toString()
            }
        }
    }

    /**
     * Permanently removes a property. Callers MUST check that the property
     * has no income or expense entries before calling this — see the
     * various *Repository.existsForProperty() functions — since
     * PropertyRepository deliberately doesn't depend on those repositories
     * itself, to avoid a circular dependency between database classes.
     *
     * Any ForeignPropertyElections rows for the property ARE deleted here
     * regardless, since — unlike income/expense entries — they're recorded
     * automatically on every foreign property (see ForeignPropertyElectionRepository
     * doc comment) and so don't represent genuine usage that should block
     * removal; they're simply cleaned up as part of it.
     */
    fun remove(id: Int) {
        transaction {
            ForeignPropertyElections.deleteWhere { ForeignPropertyElections.propertyId eq id }
            Properties.deleteWhere { Properties.id eq id }
        }
    }

    private fun ResultRow.toProperty() = Property(
        id                     = this[Properties.id],
        userId                 = this[Properties.userId],
        address                = this[Properties.address],
        postcode               = this[Properties.postcode],
        propertyType           = PropertyType.valueOf(this[Properties.propertyType]),
        countryCode            = this[Properties.countryCode],
        hmrcPropertyId         = this[Properties.hmrcPropertyId],
        hmrcRegisteredAt       = this[Properties.hmrcRegisteredAt],
        hmrcRegisteredTaxYear  = this[Properties.hmrcRegisteredTaxYear],
        createdAt              = this[Properties.createdAt],
        endedAt                = this[Properties.endedAt],
        supersededAt           = this[Properties.supersededAt],
    )
}