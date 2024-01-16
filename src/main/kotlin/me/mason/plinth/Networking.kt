package me.mason.plinth

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.nio.channels.NotYetConnectedException
import java.nio.charset.Charset
import java.util.*
import kotlin.coroutines.*

suspend fun <T> AsynchronousSocketChannel.read(
    buffer: ByteBuffer,
    setReadMark: (Int) -> (Unit),
    getReadMark: () -> (Int),
    size: Int,
    method: ByteBuffer.() -> (T),
    server: Server? = null,
    index: Int? = null,
    disconnectLock: Mutex? = null
): T {
    if (buffer.position() - getReadMark() >= size) {
        val before = buffer.position()
        buffer.position(getReadMark())
        val result = buffer.method()
        setReadMark(buffer.position())
        buffer.position(before)
        return result
    } else {
        if (buffer.remaining() < size) {
            buffer.position(getReadMark())
            buffer.compact()
            setReadMark(0)
        }
        while(buffer.position() - getReadMark() < size) {
            try {
                suspendCoroutine<Unit> {
                    read(buffer, null, object : CompletionHandler<Int, Void?> {
                        override fun completed(count: Int, attachment: Void?) {
                            if (count == -1) {
                                it.resumeWithException(EOFException())
                                return
                            }
                            it.resume(Unit)
                        }
                        override fun failed(throwable: Throwable, attachment: Void?) {
                            it.resumeWithException(throwable)
                        }
                    })
                }
            } catch (throwable: Throwable) {
                if (server != null && index != null && disconnectLock != null) {
                    disconnectLock.withLock { server.active.clear(index) }
                }; throw throwable // I guess rethrow?
            }
        }
        val before = buffer.position()
        buffer.position(getReadMark())
        val result = buffer.method()
        setReadMark(buffer.position())
        buffer.position(before)
        return result
    }
}

//TODO: make write better
suspend fun <T> AsynchronousSocketChannel.write(
    buffer: ByteBuffer,
    method: ByteBuffer.(T) -> (Unit),
    value: T,
    server: Server? = null,
    index: Int? = null,
    disconnectLock: Mutex? = null
) {
    buffer.method(value)
    buffer.flip()
    try {
        suspendCoroutine<Unit> {
            write(buffer, null, object : CompletionHandler<Int, Void?> {
                override fun completed(count: Int, attachment: Void?) {
                    //TODO: handle didn't write all
                    it.resume(Unit)
                }
                override fun failed(throwable: Throwable, attachment: Void?) {
                    it.resumeWithException(throwable)
                }
            })
        }
    } catch (throwable: Throwable) {
        if (server != null && index != null && disconnectLock != null) {
            disconnectLock.withLock { server.active.clear(index) }
        }; throw throwable // I guess rethrow?
    }
    buffer.clear()
}

interface Read {
    suspend fun byte(): Byte
    suspend fun short(): Short
    suspend fun int(): Int
    suspend fun long(): Long
    suspend fun bytes(amount: Int): ByteArray
    suspend fun string(charset: Charset = Charsets.UTF_8): String
}

interface Write {
    suspend fun byte(value: Byte)
    suspend fun short(value: Short)
    suspend fun int(value: Int)
    suspend fun long(value: Long)
    suspend fun bytes(value: ByteArray)
    suspend fun string(value: String, charset: Charset = Charsets.UTF_8)
}

interface Connection : Read, Write

val CONNECTION_STUB = object : Connection {
    override suspend fun byte(): Byte = throw NotYetConnectedException()
    override suspend fun short(): Short = throw NotYetConnectedException()
    override suspend fun int(): Int = throw NotYetConnectedException()
    override suspend fun long(): Long = throw NotYetConnectedException()
    override suspend fun bytes(amount: Int): ByteArray = throw NotYetConnectedException()
    override suspend fun string(charset: Charset): String = throw NotYetConnectedException()
    override suspend fun byte(value: Byte) = throw NotYetConnectedException()
    override suspend fun short(value: Short) = throw NotYetConnectedException()
    override suspend fun int(value: Int) = throw NotYetConnectedException()
    override suspend fun long(value: Long) = throw NotYetConnectedException()
    override suspend fun bytes(value: ByteArray) = throw NotYetConnectedException()
    override suspend fun string(value: String, charset: Charset) = throw NotYetConnectedException()
}

fun AsynchronousSocketChannel.connection(
    maxBuffer: Int = Short.MAX_VALUE.toInt(),
    server: Server? = null,
    index: Int? = null,
    disconnectLock: Mutex? = null
): Connection {
    val channel = this
    val read = ByteBuffer.allocate(maxBuffer)
    val write = ByteBuffer.allocate(maxBuffer)
    val readLock = Mutex()
    val writeLock = Mutex()
    var readMark = 0 // cant access mark value in Buffer without vm option and reflection
    val setReadMark: (Int) -> (Unit) = { readMark = it }
    val getReadMark: () -> (Int) = { readMark }
    return object : Connection {
        override suspend fun byte(): Byte = readLock.withLock {
            channel.read(read, setReadMark, getReadMark, 1, ByteBuffer::get, server, index, disconnectLock)
        }
        override suspend fun short(): Short = readLock.withLock {
            channel.read(read, setReadMark, getReadMark, 2, ByteBuffer::getShort, server, index, disconnectLock)
        }
        override suspend fun int(): Int = readLock.withLock {
            channel.read(read, setReadMark, getReadMark, 4, ByteBuffer::getInt, server, index, disconnectLock)
        }
        override suspend fun long(): Long = readLock.withLock {
            channel.read(read, setReadMark, getReadMark, 8, ByteBuffer::getLong, server, index, disconnectLock)
        }
        //TODO: make this one nio operation later
        override suspend fun bytes(amount: Int): ByteArray = readLock.withLock {
            ByteArray(amount) { byte() }
        }
        override suspend fun string(charset: Charset): String = readLock.withLock {
            String(bytes(int()))
        }
        override suspend fun byte(value: Byte) = writeLock.withLock {
            channel.write(write, ByteBuffer::put, value, server, index, disconnectLock)
        }
        override suspend fun short(value: Short) = writeLock.withLock {
            channel.write(write, ByteBuffer::putShort, value, server, index, disconnectLock)
        }
        override suspend fun int(value: Int) = writeLock.withLock {
            channel.write(write, ByteBuffer::putInt, value, server, index, disconnectLock)
        }
        override suspend fun long(value: Long) = writeLock.withLock {
            channel.write(write, ByteBuffer::putLong, value, server, index, disconnectLock)
        }
        //TODO: make this one nio operation later
        override suspend fun bytes(value: ByteArray) = writeLock.withLock {
            value.forEach { byte(it) }
        }
        override suspend fun string(value: String, charset: Charset) = writeLock.withLock {
            int(value.length)
            bytes(value.toByteArray(charset))
        }
    }
}

interface Server {
    val active: BitSet
    val connections: Array<Connection>
    suspend fun forActive(block: suspend Connection.(Int) -> (Unit))
    suspend fun onConnect(block: suspend Connection.(Int) -> (Unit))
    suspend fun onDisconnect(block: suspend (Int) -> (Unit))
}

//TODO: handle disconnecting
suspend fun AsynchronousChannelGroup.server(
    address: InetSocketAddress,
    maxConnections: Int = 256,
    maxBuffer: Int = Short.MAX_VALUE.toInt(),
    block: suspend Server.() -> (Unit)
) = coroutineScope {
    val group = this@server
    val channel = withContext(Dispatchers.IO) {
        AsynchronousServerSocketChannel.open(group).bind(address)
    }
    val connectLock = Mutex()
    val disconnectLock = Mutex()
    val whenConnect = Collections.synchronizedList(ArrayList<suspend Connection.(Int) -> (Unit)>())
    val whenDisconnect = Collections.synchronizedList(ArrayList<suspend (Int) -> (Unit)>())
    val server = object : Server {
        override val active = BitSet(maxConnections)
        override val connections = Array(maxConnections) { CONNECTION_STUB }
        override suspend fun forActive(block: suspend Connection.(Int) -> Unit) {
            var idx = active.nextSetBit(0)
            while (idx != -1) {
                connections[idx].block(idx)
                idx = active.nextSetBit(idx + 1)
            }
        }
        override suspend fun onConnect(block: suspend Connection.(Int) -> Unit) = whenConnect.plusAssign(block)
        override suspend fun onDisconnect(block: suspend (Int) -> Unit) = whenDisconnect.plusAssign(block)
    }
    server.block()
    while (isActive) {
        val id = connectLock.withLock { server.active.nextClearBit(0) }
        suspendCoroutine { continuation ->
            channel.accept(null, object : CompletionHandler<AsynchronousSocketChannel, Void?> {
                override fun completed(channel: AsynchronousSocketChannel, attachment: Void?) {
                    println("$id connected????")
                    server.connections[id] = channel.connection(maxBuffer, server, id, disconnectLock).also {
                        continuation.resume(it)
                    }
                }
                override fun failed(throwable: Throwable, attachment: Void?) {
                    continuation.resume(null)
                }
            })
        }?.apply {
            connectLock.withLock { server.active.set(id) }
            whenConnect.forEach { it(id) }
        }
    }
}

suspend fun AsynchronousChannelGroup.client(
    address: InetSocketAddress,
    maxBuffer: Int = Short.MAX_VALUE.toInt(),
    block: suspend Connection.() -> (Unit)
) = suspendCoroutine {
    val channel = AsynchronousSocketChannel.open(this)
    channel.connect(address, it, object : CompletionHandler<Void?, Continuation<Connection>> {
        override fun completed(result: Void?, continuation: Continuation<Connection>) =
            continuation.resume(channel.connection(maxBuffer))
        override fun failed(throwable: Throwable, continuation: Continuation<Connection>) =
            continuation.resumeWithException(throwable)
    })
}.block()