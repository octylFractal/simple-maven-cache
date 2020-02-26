/*
 * Copyright (c) Octavia Togami <https://octyl.net>
 * Copyright (c) contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.octyl.mavencache

import io.ktor.http.ContentType
import io.ktor.util.KtorExperimentalAPI
import io.ktor.util.split
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope

class UpstreamResponse(
    val contentLength: Long?,
    val contentType: ContentType,
    val content: ByteReadChannel
) {
    init {
        require(contentLength == null || contentLength >= 0) {
            "Content length must be greater than or equal to zero"
        }
    }

    @UseExperimental(KtorExperimentalAPI::class)
    fun split(coroutineScope: CoroutineScope): Pair<UpstreamResponse, UpstreamResponse> {
        val (left, right) = content.split(coroutineScope)
        return UpstreamResponse(contentLength, contentType, left) to
            UpstreamResponse(contentLength, contentType, right)
    }
}
