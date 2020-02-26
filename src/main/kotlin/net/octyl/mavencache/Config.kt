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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.octyl.mavencache.io.readerFromStream
import net.octyl.mavencache.io.writerFromStream
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
        logger.info("Using maven cache directory: ${cacheDirectory.toAbsolutePath()}")
    }

    private fun saveTo(properties: MutableProps) {
        properties.putAll(mapOf(
            SERVERS to servers.joinToString(", "),
            CACHE_DIRECTORY to cacheDirectory.toAbsolutePath().toString()
        ))
    }

    suspend fun saveTo(location: Path) {
        val properties = LinkedHashMap<String, String>().also { saveTo(it) }
        withContext(Dispatchers.IO) {
            Files.createDirectories(location.parent)
            readerFromStream { Files.newOutputStream(location) }.channel.use {
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
                    "https://plugins.gradle.com/m2"
                ),
                cacheDirectory = Path.of("./maven")
            ).saveTo(this)
        }

        suspend fun loadFrom(location: Path): Config {
            val props = withContext(Dispatchers.IO) {
                val channel = when {
                    Files.exists(location) -> writerFromStream { Files.newInputStream(location) }.channel
                    else -> ByteReadChannel("")
                }
                loadPropertiesFrom(channel, defaults = DEFAULT_PROPERTIES)
            }
            val cacheDirectory = Path.of(props.getValue(CACHE_DIRECTORY))
            withContext(Dispatchers.IO) {
                Files.createDirectories(cacheDirectory)
            }
            return Config(
                props.getValue(SERVERS).split(',')
                    .map { it.trim() }
                    .filterNot { it.isEmpty() },
                cacheDirectory
            )
        }
    }
}
