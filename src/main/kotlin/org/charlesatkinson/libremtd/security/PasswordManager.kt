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

package org.charlesatkinson.libremtd.security

import org.mindrot.jbcrypt.BCrypt
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

object PasswordManager {
    private const val BCRYPT_ROUNDS = 12
    
    fun hashPassword(password: String): String {
        require(password.length >= 8) { "Password must be at least 8 characters" }
        return BCrypt.hashpw(password, BCrypt.gensalt(BCRYPT_ROUNDS))
    }
    
    fun verifyPassword(password: String, hash: String): Boolean {
        return try {
            BCrypt.checkpw(password, hash)
        } catch (e: Exception) {
            logger.error(e) { "Error verifying password" }
            false
        }
    }
    
    fun validatePasswordStrength(password: String): ValidationResult {
        val errors = mutableListOf<String>()
        
        if (password.length < 8) {
            errors.add("Password must be at least 8 characters")
        }
        if (!password.any { it.isUpperCase() }) {
            errors.add("Password must contain at least one uppercase letter")
        }
        if (!password.any { it.isLowerCase() }) {
            errors.add("Password must contain at least one lowercase letter")
        }
        if (!password.any { it.isDigit() }) {
            errors.add("Password must contain at least one number")
        }
        
        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }
    
    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val errors: List<String>) : ValidationResult()
    }
}