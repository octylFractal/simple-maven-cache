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
import io.ktor.client.features.HttpRequestTimeoutException
import io.ktor.client.features.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpStatement
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.network.sockets.ConnectTimeoutException
import io.ktor.network.sockets.SocketTimeoutException
import io.ktor.utils.io.WriterJob
import io.ktor.utils.io.core.ExperimentalIoApi
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.writer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.streams.asSequence

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

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                followRedirects(true)
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 5000
            requestTimeoutMillis = 5000
            socketTimeoutMillis = 5000
        }
    }

    suspend fun get(path: String): UpstreamResponse? {
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
        if (!Files.exists(cachePath)) {
            getAndStoreResponse(path, cachePath) ?: return null
        }
        return useExistingFile(path, cachePath)
    }

    private suspend fun getAndStoreResponse(path: String, cachePath: Path): Unit? {
        var lock: Mutex
        while (true) {
            var isNewLock = false
            lock = writingMutexes.computeIfAbsent(cachePath) {
                isNewLock = true
                Mutex(locked = true)
            }
            if (isNewLock) {
                // new lock is ours, use it
                break
            }
            // someone is already downloading it, wait for them to finish & return
            lock.withLock { }
            // check if they got it
            if (Files.exists(cachePath)) {
                return Unit
            }
            // otherwise, re-acquire lock
        }
        try {
            var savedContent = false
            for (server in servers) {
                logger.debug("Trying to get '$path' from '$server'")
                try {
                    client.get<HttpStatement>("$server/$path").execute { response ->
                        if (response.status.isSuccess()) {
                            logger.debug("Found '$path' at '$server'")
                            saveResponseToCache(UpstreamResponse(
                                response.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
                                ContentType.Application.OctetStream,
                                response.content
                            ), cachePath)
                            savedContent = true
                        } else {
                            logger.debug("'$server' said ${response.status} for '$path'")
                        }
                    }
                    if (savedContent) {
                        break
                    }
                } catch (e: Exception) {
                    when (e) {
                        is HttpRequestTimeoutException,
                        is ConnectTimeoutException,
                        is SocketTimeoutException,
                        is IOException -> {
                            logger.warn("Failed to connect to '$server':\n${e.printToString()}")
                        }
                        else -> throw e
                    }
                }
            }
            // remove the lock from the table now
            // either we have written to cachePath, and all will see it
            // or we didn't, and they can try to download again
            writingMutexes.remove(cachePath, lock)
            return when {
                savedContent -> Unit
                else -> null
            }
        } finally {
            lock.unlock()
        }
    }

    private suspend fun saveResponseToCache(upstreamResponse: UpstreamResponse, cachePath: Path) {
        withContext(Dispatchers.IO) {
            val temporaryFile = Files.createTempFile(cacheDirectory, ".", ".tmp")
            try {
                Files.newOutputStream(temporaryFile).use { outputStream ->
                    upstreamResponse.content.copyTo(
                        outputStream,
                        limit = upstreamResponse.contentLength ?: Long.MAX_VALUE
                    )
                }
                Files.createDirectories(cachePath.parent)
                Files.move(temporaryFile, cachePath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporaryFile)
            }
            logger.debug("Saved '$cachePath' for later.")
        }
    }

    @UseExperimental(ExperimentalIoApi::class)
    private fun useExistingFile(path: String, cachePath: Path): UpstreamResponse {
        logger.debug("Serving '$cachePath' for '$path'")
        return when {
            Files.isDirectory(cachePath) -> {
                UpstreamResponse(null, ContentType.Text.Html, serveIndex(cachePath).channel)
            }
            else ->
                UpstreamResponse(
                    Files.size(cachePath),
                    ContentType.Application.OctetStream,
                    Files.newInputStream(cachePath).toByteReadChannel()
                )
        }
    }

    private fun serveIndex(cachePath: Path): WriterJob {
        return GlobalScope.writer {
            withContext(Dispatchers.IO) {
                channel.writeStringUtf8("""
                    <!doctype html>
                    <html lang="en">
                    <body>
                    <ul>
                """.trimIndent())
                Files.list(cachePath).use { files ->
                    val visibleFiles = files.asSequence()
                        .filterNot { it.fileName.toString().startsWith(".") }
                    for (path in visibleFiles) {
                        val suffix = when {
                            Files.isDirectory(path) -> "/"
                            else -> ""
                        }
                        val relPath = "${path.fileName}$suffix"
                        val href = URLEncoder.encode(relPath, StandardCharsets.UTF_8)
                            // don't encode slashes
                            .replace("%2F", "/")
                        channel.writeStringUtf8("""
                            <li>
                            <a href="$href">
                            $relPath
                            </a>
                            </li>
                        """.trimIndent())
                    }
                }
                channel.writeStringUtf8("""
                    </ul>
                    </body>
                    </html>
                """.trimIndent())
            }
        }
    }

    override fun close() {
        client.close()
    }

}
