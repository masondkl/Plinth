package me.mason.plinth

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.*

class UnconnectedException : IllegalAccessError()

suspend fun <T> AsynchronousSocketChannel.read(
    buffer: ByteBuffer,
    setReadMark: (Int) -> (Unit),
    getReadMark: () -> (Int),
    size: Int,
    method: ByteBuffer.() -> (T)
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
            suspendCoroutine {
                read(buffer, null, object : CompletionHandler<Int, Void?> {
                    override fun completed(count: Int, attachment: Void?) {
                        it.resume(Unit)
                    }
                    override fun failed(throwable: Throwable, attachment: Void?) {
                        it.resumeWithException(throwable)
                    }
                })
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
    value: T
) {
    buffer.method(value)
    buffer.flip()
    suspendCoroutine {
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

val CONNECTION_STUB = object : Connection {
    override suspend fun byte(): Byte = throw UnconnectedException()
    override suspend fun short(): Short = throw UnconnectedException()
    override suspend fun int(): Int = throw UnconnectedException()
    override suspend fun long(): Long = throw UnconnectedException()
    override suspend fun bytes(amount: Int): ByteArray = throw UnconnectedException()
    override suspend fun string(charset: Charset): String = throw UnconnectedException()
    override suspend fun byte(value: Byte) = throw UnconnectedException()
    override suspend fun short(value: Short) = throw UnconnectedException()
    override suspend fun int(value: Int) = throw UnconnectedException()
    override suspend fun long(value: Long) = throw UnconnectedException()
    override suspend fun bytes(value: ByteArray) = throw UnconnectedException()
    override suspend fun string(value: String, charset: Charset) = throw UnconnectedException()
}

interface Connection : Read, Write

fun AsynchronousSocketChannel.connection(maxBuffer: Int = Short.MAX_VALUE.toInt()): Connection {
    val channel = this
    val read = ByteBuffer.allocate(maxBuffer)
    val write = ByteBuffer.allocate(maxBuffer)
    val readLock = Mutex()
    val writeLock = Mutex()
    var readMark = 0 // cant access mark without vm option and reflection
    val setReadMark: (Int) -> (Unit) = { readMark = it }
    val getReadMark: () -> (Int) = { readMark }
    return object : Connection {
        override suspend fun byte(): Byte = readLock.withLock {
            channel.read(read, setReadMark, getReadMark, 1, ByteBuffer::get)
        }
        override suspend fun short(): Short = readLock.withLock {
            channel.read(read, setReadMark, getReadMark, 2, ByteBuffer::getShort)
        }
        override suspend fun int(): Int = readLock.withLock {
            channel.read(read, setReadMark, getReadMark, 4, ByteBuffer::getInt)
        }
        override suspend fun long(): Long = readLock.withLock {
            channel.read(read, setReadMark, getReadMark, 8, ByteBuffer::getLong)
        }
        //TODO: make this one nio operation later
        override suspend fun bytes(amount: Int): ByteArray {
            return ByteArray(amount) { byte() }
        }
        override suspend fun string(charset: Charset): String {
            return String(bytes(int()))
        }
        override suspend fun byte(value: Byte) = writeLock.withLock {
            channel.write(write, ByteBuffer::put, value)
        }
        override suspend fun short(value: Short) {
            channel.write(write, ByteBuffer::putShort, value)
        }
        override suspend fun int(value: Int) {
            channel.write(write, ByteBuffer::putInt, value)
        }
        override suspend fun long(value: Long) {
            channel.write(write, ByteBuffer::putLong, value)
        }
        //TODO: make this one nio operation later
        override suspend fun bytes(value: ByteArray) {
            value.forEach { byte(it) }
        }
        override suspend fun string(value: String, charset: Charset) {
            int(value.length)
            bytes(value.toByteArray(charset))
        }
    }
}

interface Server {
    val active: Int
    val connections: Array<Connection>
    suspend fun onConnect(block: suspend Connection.(Int) -> (Unit))
}

suspend fun AsynchronousChannelGroup.server(
    address: InetSocketAddress,
    max: Int = 256,
    maxBuffer: Int = Short.MAX_VALUE.toInt()
): Server {
    val group = this
    val channel = withContext(Dispatchers.IO) {
        AsynchronousServerSocketChannel.open(group).bind(address)
    }
    val whenConnect = Collections.synchronizedList(ArrayList<suspend Connection.(Int) -> (Unit)>())
    val active = AtomicInteger()
    val server = object : Server {
        override val active: Int get() = active.get()
        override val connections = Array(max) { CONNECTION_STUB }
        override suspend fun onConnect(block: suspend Connection.(Int) -> Unit) = whenConnect.plusAssign(block)
    }
    CoroutineScope(coroutineContext).launch {
        while (isActive) {
            suspendCoroutine { continuation ->
                channel.accept(null, object : CompletionHandler<AsynchronousSocketChannel, Void?> {
                    override fun completed(channel: AsynchronousSocketChannel, attachment: Void?) {
                        server.connections[active.getAndIncrement()] = channel.connection(maxBuffer).also {
                            continuation.resume(it)
                        }
                    }
                    override fun failed(throwable: Throwable, attachment: Void?) {
                        continuation.resumeWithException(throwable)
                    }
                })
            }.apply { whenConnect.forEach { it(active.get() - 1) } }
        }
    }
    return server
}

suspend fun AsynchronousChannelGroup.client(
    address: InetSocketAddress,
    maxBuffer: Int = Short.MAX_VALUE.toInt()
) = suspendCoroutine {
    val channel = AsynchronousSocketChannel.open(this)
    channel.connect(address, it, object : CompletionHandler<Void?, Continuation<Connection>> {
        override fun completed(result: Void?, continuation: Continuation<Connection>) =
            continuation.resume(channel.connection(maxBuffer))
        override fun failed(throwable: Throwable, continuation: Continuation<Connection>) =
            continuation.resumeWithException(throwable)
    })
}