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

import io.ktor.util.cio.use
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.ExperimentalIoApi
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.reader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path

data class Config(
    val servers: List<String>,
    val cacheDirectory: Path
) {
    init {
        require(servers.isNotEmpty()) { "No servers provided!" }
    }

    fun logTo(logger: Logger) {
        logger.info("Using downstream servers:")
        for (server in servers) {
            logger.info("\t- $server")
        }
        logger.info("Using maven cache directory: $cacheDirectory")
    }

    private fun saveTo(properties: MutableProps) {
        properties.putAll(mapOf(
            SERVERS to servers.joinToString(", "),
            CACHE_DIRECTORY to cacheDirectory.toString()
        ))
    }

    suspend fun saveTo(location: Path) {
        val properties = LinkedHashMap<String, String>().also { saveTo(it) }
        withContext(Dispatchers.IO) {
            Files.createDirectories(location.parent)
            reader {
                withContext(Dispatchers.IO) {
                    Files.newOutputStream(location).use { output ->
                        channel.copyTo(output, limit = Long.MAX_VALUE)
                    }
                }
            }.channel.use {
                properties.saveTo(this)
            }
        }
    }

    companion object {
        private const val SERVERS = "servers"
        private const val CACHE_DIRECTORY = "cache-directory"

        private val DEFAULT_PROPERTIES = LinkedHashMap<String, String>().apply {
            Config(
                servers = listOf(
                    "https://jcenter.bintray.com",
                    "https://plugins.gradle.org/m2"
                ),
                cacheDirectory = Path.of("./maven").toAbsolutePath()
            ).saveTo(this)
        }

        @UseExperimental(ExperimentalIoApi::class)
        suspend fun loadFrom(location: Path): Config {
            val props = withContext(Dispatchers.IO) {
                val channel = when {
                    Files.exists(location) -> Files.newInputStream(location).toByteReadChannel()
                    else -> ByteReadChannel("")
                }
                loadPropertiesFrom(channel, defaults = DEFAULT_PROPERTIES)
            }
            val cacheDirectory = Path.of(props.getValue(CACHE_DIRECTORY))
            return withContext(Dispatchers.IO) {
                Files.createDirectories(cacheDirectory)
                Config(
                    props.getValue(SERVERS).split(',')
                        .map { it.trim() }
                        .filterNot { it.isEmpty() },
                    cacheDirectory.toRealPath()
                )
            }
        }
    }
}
