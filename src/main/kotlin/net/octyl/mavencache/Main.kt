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
import io.ktor.response.respondOutputStream
import io.ktor.response.respondText
import io.ktor.routing.Routing
import io.ktor.routing.get
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.pipeline.PipelineContext
import io.ktor.utils.io.jvm.javaio.copyTo
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val LOGGER = LoggerFactory.getLogger("net.octyl.mavencache.MainKt")

fun main() {
    embeddedServer(
        Netty,
        host = "localhost",
        port = 5956,
        watchPaths = listOf("MainKt"),
        module = Application::mainModule
    ).start(wait = true)
}

fun Application.mainModule() {
    val downstreamServers = listOf("https://jcenter.bintray.com")
    val cacheDirectory = Path.of("./maven")
    LOGGER.info("Using downstream servers:")
    for (server in downstreamServers) {
        LOGGER.info("\t- $server")
    }
    LOGGER.info("Using maven cache directory: ${cacheDirectory.toAbsolutePath()}")
    val cacheManager = CacheManager(downstreamServers, cacheDirectory)

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
    val path = call.parameters.getAll("path").orEmpty().joinToString("/")
    cacheManager.get(path) { upstream ->
        if (upstream == null) {
            call.respondText("No such entry found.",
                contentType = ContentType.Text.Plain,
                status = HttpStatusCode.NotFound)
            return@get
        }
        call.respondOutputStream(ContentType.Application.OctetStream, status = HttpStatusCode.OK) {
            upstream.content.copyTo(this, limit = upstream.contentLength ?: Long.MAX_VALUE)
        }
    }
}
