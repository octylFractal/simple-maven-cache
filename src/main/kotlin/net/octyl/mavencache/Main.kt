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

import io.ktor.application.Application
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.AutoHeadResponse
import io.ktor.features.CallLogging
import io.ktor.features.DefaultHeaders
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.request.uri
import io.ktor.response.respond
import io.ktor.response.respondOutputStream
import io.ktor.response.respondText
import io.ktor.routing.Routing
import io.ktor.routing.get
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.pipeline.PipelineContext
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

private val LOGGER = LoggerFactory.getLogger("net.octyl.mavencache.MainKt")

fun main(argv: Array<String>) {
    embeddedServer(
        Netty,
        host = "localhost",
        port = 5956,
        module = { mainModule(argv) }
    ).start(wait = true)
}

fun Application.mainModule(argv: Array<String>) {
    val configLocation = Path.of(when {
        argv.size >= 2 && argv[0] == "--config-location" -> argv[1]
        else -> "/etc/simple-maven-cache.properties"
    })
    LOGGER.info("Loading configuration from ${configLocation.toAbsolutePath()}")
    val config = runBlocking {
        Config.loadFrom(configLocation).also { it.saveTo(configLocation) }
    }
    config.logTo(LOGGER)
    val cacheManager = CacheManager(config.servers, config.cacheDirectory)
    install(DefaultHeaders) {
        header(HttpHeaders.Server, "simple-maven-cache")
    }
    install(CallLogging)
    install(AutoHeadResponse)
    install(Routing) {
        get("/{path...}") {
            handleMavenRequest(cacheManager)
        }
    }
}

private suspend fun PipelineContext<Unit, ApplicationCall>.handleMavenRequest(cacheManager: CacheManager) {
    try {
        val path = call.parameters.getAll("path").orEmpty().joinToString("/")
        val upstream = cacheManager.get(path)
        if (upstream == null) {
            call.respondText("No such entry found.",
                contentType = ContentType.Text.Plain,
                status = HttpStatusCode.NotFound)
            return
        }
        call.respond(object : OutgoingContent.ReadChannelContent() {
            override fun readFrom() = upstream.content
            override val contentLength = upstream.contentLength
            override val status = HttpStatusCode.OK
        })
    } catch (e: Exception) {
        LOGGER.error("Failed to respond for ${call.request.uri}", e)
        call.respondText(contentType = ContentType.Text.Plain, status = HttpStatusCode.InternalServerError) {
            e.printToString()
        }
    }
}
