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

package org.charlesatkinson.libremtd.network.interceptors

import okhttp3.Interceptor
import okhttp3.Response
import org.charlesatkinson.libremtd.security.FraudPreventionHeaders
import org.charlesatkinson.libremtd.network.ClientContext

/**
 * Automatic header injection
 * 
 * Zero duplication
 * Centralised compliance logic
 * Uses OkHttp.  TODO: how?
 */
class FraudPreventionInterceptor(
    private val headers: FraudPreventionHeaders,
    private val contextProvider: () -> ClientContext
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val context = contextProvider()
        val fraudHeaders = headers.buildHeaders(context)

        val request = chain.request().newBuilder().apply {
            fraudHeaders.forEach { (k, v) ->
                header(k, v)
            }
        }.build()

        return chain.proceed(request)
    }
}
