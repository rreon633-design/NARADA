package com.narada.android.platform

import java.io.InputStream

internal fun InputStream.readAtMost(limit: Int): ByteArray {
    require(limit in 1..16384)
    val bytes = ByteArray(limit)
    var offset = 0
    while (offset < limit) {
        val count = read(bytes, offset, limit - offset)
        if (count < 0) break
        if (count == 0) {
            val next = read()
            if (next < 0) break
            bytes[offset++] = next.toByte()
            continue
        }
        offset += count
    }
    return bytes.copyOf(offset)
}
