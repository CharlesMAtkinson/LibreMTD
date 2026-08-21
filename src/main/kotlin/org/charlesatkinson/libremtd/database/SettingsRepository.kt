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

import mu.KotlinLogging
import org.charlesatkinson.libremtd.database.tables.HmrcSettings as HmrcSettingsTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.time.LocalDateTime

class SettingsRepository {

    private val logger = KotlinLogging.logger {}

    fun load(userId: Int): HmrcSettings? {
        logger.info { "Loading settings for userId=$userId" }
        return transaction {
            HmrcSettingsTable
                .selectAll()
                .where { HmrcSettingsTable.userId eq userId }
                .singleOrNull()
                ?.let { row ->
                    HmrcSettings(
                        userId            = row[HmrcSettingsTable.userId],
                        clientId          = row[HmrcSettingsTable.clientId],
                        clientSecret      = row[HmrcSettingsTable.clientSecret],
                        fullName          = row[HmrcSettingsTable.fullName],
                        dateOfBirth       = row[HmrcSettingsTable.dateOfBirth],
                        addressLine1      = row[HmrcSettingsTable.addressLine1],
                        addressLine2      = row[HmrcSettingsTable.addressLine2],
                        addressLine3      = row[HmrcSettingsTable.addressLine3],
                        postcode          = row[HmrcSettingsTable.postcode],
                        nino              = row[HmrcSettingsTable.nino],
                        utr               = row[HmrcSettingsTable.utr],
                        businessIdUk      = row[HmrcSettingsTable.businessIdUk],
                        businessIdForeign = row[HmrcSettingsTable.businessIdForeign],
                    )
                }
        }
    }

    fun save(settings: HmrcSettings) {
        logger.info { "Saving settings for userId=${settings.userId}" }
        transaction {
            HmrcSettingsTable.upsert {
                it[userId]            = settings.userId
                it[clientId]          = settings.clientId
                it[clientSecret]      = settings.clientSecret
                it[fullName]          = settings.fullName
                it[dateOfBirth]       = settings.dateOfBirth
                it[addressLine1]      = settings.addressLine1
                it[addressLine2]      = settings.addressLine2
                it[addressLine3]      = settings.addressLine3
                it[postcode]          = settings.postcode
                it[nino]              = settings.nino
                it[utr]               = settings.utr
                it[businessIdUk]      = settings.businessIdUk
                it[businessIdForeign] = settings.businessIdForeign
                it[updatedAt]         = LocalDateTime.now().toString()
            }
        }
    }

    fun clear(userId: Int) {
        logger.info { "Clearing settings for userId=$userId" }
        transaction {
            HmrcSettingsTable.deleteWhere { HmrcSettingsTable.userId eq userId }
        }
    }
}