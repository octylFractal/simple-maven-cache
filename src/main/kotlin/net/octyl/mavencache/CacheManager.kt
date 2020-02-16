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

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.HttpStatement
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.close
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.discard
import io.ktor.utils.io.streams.asInput
import io.ktor.utils.io.streams.asOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.octyl.mavencache.io.readerFrom
import net.octyl.mavencache.io.writerFrom
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

typealias ResponseHandler = suspend (UpstreamResponse?) -> Unit

class CacheManager(
    private val servers: List<String>,
    private val cacheDirectory: Path
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        Files.createDirectories(cacheDirectory)
    }

    private val writingMutexes = ConcurrentHashMap<Path, Mutex>()

    private fun cachePath(path: String): Path {
        require(!path.startsWith("/")) { "Path should not start with a slash" }
        return cacheDirectory.resolve(path)
    }

    private val client = HttpClient(OkHttp)

    suspend fun get(path: String, block: ResponseHandler) {
        logger.info("Request for '$path'")
        val cachePath = cachePath(path)
        /*
         * Invariants:
         * - If `cachePatch` exists, then it is a fully downloaded + valid file
         * - Else if `writingMutexes` contains a lock for that path,
         *   the file is currently being prepared by another thread.
         *   When the lock is released, if cachePath doesn't exist we failed to DL
         *   so try again, to ensure we fail out properly
         * - Otherwise, no one is writing, and `writingMutexes` can be cIfA'd for
         *   the above lock, to ensure a unique lock is assigned.
         */
        if (Files.exists(cachePath)) {
            // Fully downloaded
            return useExistingFile(path, cachePath, block)
        }
        // check / make lock
        // if we make one, _lock it immediately_
        val lock = checkLockLoop(path, cachePath, block) ?: return
        // use the lock!
        try {
            coroutineScope {
                for (server in servers) {
                    client.get<HttpStatement>("$server/$path").execute { response ->
                        if (response.status.isSuccess()) {
                            logger.info("Found '$path' at '$server'")
                            val upstreamResponses = UpstreamResponse(
                                response.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
                                response.content
                            ).split(this)
                            launch(Dispatchers.IO) {
                                saveResponseToCache(upstreamResponses.second, cachePath)
                            }
                            block(upstreamResponses.first)
                        }
                    }
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private suspend fun checkLockLoop(path: String, cachePath: Path, block: ResponseHandler): Mutex? {
        while (true) {
            var isNewLock = false
            val lock = writingMutexes.computeIfAbsent(cachePath) {
                isNewLock = true
                Mutex(locked = true)
            }
            if (isNewLock) {
                return lock
            }
            // there's another lock in the table, lock on it and then use cachePath
            lock.withLock {}
            if (Files.exists(cachePath)) {
                useExistingFile(path, cachePath, block)
                return null
            }
            // didn't exist. try to setup another lock to download
            // but it might be the case that another coroutine beats us, in which case
            // just try and hold it again.
        }
    }

    private suspend fun saveResponseToCache(upstreamResponse: UpstreamResponse, cachePath: Path) {
        withContext(Dispatchers.IO) {
            val temporaryFile = Files.createTempFile(cacheDirectory, ".", ".tmp")
            try {
                // silence intellij, near-0 runtime cost
                withContext(Dispatchers.IO) {
                    val output = readerFrom(Files.newOutputStream(temporaryFile).asOutput()).channel
                    upstreamResponse.content.copyTo(
                        output,
                        limit = upstreamResponse.contentLength ?: Long.MAX_VALUE
                    )
                    output.close()
                }
                Files.createDirectories(cachePath.parent)
                Files.move(temporaryFile, cachePath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporaryFile)
            }
            logger.info("Saved '$cachePath' for later.")
        }
    }

    private suspend fun useExistingFile(path: String, cachePath: Path, block: ResponseHandler) {
        logger.info("Serving '$cachePath' for '$path'")
        withContext(Dispatchers.IO) {
            val inputStream = Files.newInputStream(cachePath)
            val input = writerFrom(inputStream.asInput()).channel
            block(UpstreamResponse(Files.size(cachePath), input))
            // dump any input we didn't use to cleanly close the stream
            input.discard()
        }
    }

    override fun close() {
        client.close()
    }

}
