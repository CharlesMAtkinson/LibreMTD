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

import org.charlesatkinson.libremtd.database.tables.Properties
import org.jetbrains.exposed.sql.*
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
                supersededAt = null,
            )
        }
    }

    fun findByUser(userId: Int): List<Property> {
        return transaction {
            Properties
                .selectAll()
                .where { (Properties.userId eq userId) and Properties.supersededAt.isNull() }
                .map { row ->
                    Property(
                        id                     = row[Properties.id],
                        userId                 = row[Properties.userId],
                        address                = row[Properties.address],
                        postcode               = row[Properties.postcode],
                        propertyType           = PropertyType.valueOf(row[Properties.propertyType]),
                        countryCode            = row[Properties.countryCode],
                        hmrcPropertyId         = row[Properties.hmrcPropertyId],
                        hmrcRegisteredAt       = row[Properties.hmrcRegisteredAt],
                        hmrcRegisteredTaxYear  = row[Properties.hmrcRegisteredTaxYear],
                        createdAt              = row[Properties.createdAt],
                        supersededAt           = row[Properties.supersededAt],
                    )
                }
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

    fun softDelete(id: Int) {
        transaction {
            Properties.update({ Properties.id eq id }) {
                it[supersededAt] = java.time.Instant.now().toString()
            }
        }
    }
}
