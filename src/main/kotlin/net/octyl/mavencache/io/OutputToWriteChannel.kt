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

package net.octyl.mavencache.io

import io.ktor.utils.io.ReaderJob
import io.ktor.utils.io.core.Output
import io.ktor.utils.io.core.writeFully
import io.ktor.utils.io.reader
import io.ktor.utils.io.streams.asOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.nio.ByteBuffer

inline fun CoroutineScope.readerFromStream(crossinline outputStream: () -> OutputStream): ReaderJob {
    return readerFrom { withContext(Dispatchers.IO) { outputStream().asOutput() } }
}

fun CoroutineScope.readerFrom(outputProvider: suspend () -> Output): ReaderJob {
    return reader(autoFlush = true) {
        val buffer = ByteBuffer.allocate(8192)
        outputProvider().use { output ->
            while (!channel.isClosedForRead) {
                channel.readAvailable(buffer)
                buffer.flip()
                output.writeFully(buffer)
                buffer.clear()
            }
        }
    }
}
