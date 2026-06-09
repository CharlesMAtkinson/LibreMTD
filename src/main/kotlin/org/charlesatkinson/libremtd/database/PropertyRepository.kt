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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

data class Property(
    val id: Int,
    val userId: Int,
    val address: String,
    val postcode: String,
    val createdAt: String,
    val supersededAt: String?,
)

object PropertyRepository {

    fun create(userId: Int, address: String, postcode: String): Property {
        return transaction {
            val id = Properties.insert {
                it[Properties.userId]    = userId
                it[Properties.address]  = address
                it[Properties.postcode] = postcode
                it[Properties.createdAt] = LocalDateTime.now().toString()
            } get Properties.id

            Property(id, userId, address, postcode, LocalDateTime.now().toString(), supersededAt = null)
        }
    }

    fun findByUser(userId: Int): List<Property> {
        return transaction {
            Properties
                .selectAll()
                .where { (Properties.userId eq userId) and Properties.supersededAt.isNull() }
                .map { row ->
                    Property(
                        id           = row[Properties.id],
                        userId       = row[Properties.userId],
                        address      = row[Properties.address],
                        postcode     = row[Properties.postcode],
                        createdAt    = row[Properties.createdAt],
                        supersededAt = row[Properties.supersededAt],
                    )
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