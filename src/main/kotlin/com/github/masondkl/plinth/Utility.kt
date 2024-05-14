package com.github.masondkl.plinth

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

suspend fun Write.string(value: String, charset: Charset = Charsets.UTF_8) {
    val bytes = value.toByteArray(charset)
    int(bytes.size)
    bytes(bytes)
}

suspend fun Read.string(charset: Charset = Charsets.UTF_8): String {
    return String(bytes(int()), charset)
}

fun ByteBuffer.putString(value: String, charset: Charset = Charsets.UTF_8) {
    val bytes = value.toByteArray(charset)
    putInt(bytes.size)
    put(bytes)
}
fun ByteBuffer.getString(charset: Charset = Charsets.UTF_8): String? {
    val size = getInt()
    if (size < 0) return null
    else if (size == 0) return ""
    val dest = ByteArray(size)
    get(dest)
    return String(dest, charset)
}

suspend fun Connection.dispatchWrites(dispatcher: CoroutineDispatcher) = CoroutineScope(dispatcher).launch {
    try { channel.consumeEach { yield(); it() } }
    catch (_: Throwable) { }
}