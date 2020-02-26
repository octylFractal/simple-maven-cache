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

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8

typealias Props = Map<String, String>
typealias MutableProps = MutableMap<String, String>

suspend fun loadPropertiesFrom(channel: ByteReadChannel, defaults: Props = mapOf()): Props {
    val props: MutableProps = LinkedHashMap(defaults)
    var lineCounter = 0
    while (!channel.isClosedForRead) {
        val line = channel.readUTF8Line(Int.MAX_VALUE) ?: break
        lineCounter++
        val importantLine = line.substringBefore('#').trim()
        if (importantLine.isEmpty()) {
            continue
        }
        val indexOfEqual = importantLine.indexOf('=')
        require(indexOfEqual >= 0) { "No '=' in line $lineCounter: '$line'" }
        val key = importantLine.substring(0, indexOfEqual).trimEnd()
        val value = importantLine.substring(indexOfEqual + 1).trimStart()
        props[key] = value
    }
    return props
}

suspend fun Props.saveTo(channel: ByteWriteChannel) {
    for ((k, v) in this) {
        channel.writeStringUtf8("$k=$v\n")
    }
}
