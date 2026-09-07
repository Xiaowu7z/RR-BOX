package com.rr.client.subscription

import java.io.ByteArrayOutputStream
import java.io.InputStream

object ImportLimits {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_NODES = 2048

    fun readUtf8(input: InputStream, maxBytes: Int = MAX_BYTES): String {
        require(maxBytes in 1..MAX_BYTES)
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - total + 1))
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= maxBytes) { "订阅内容过大，最多允许 8 MiB" }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF")
    }
}
