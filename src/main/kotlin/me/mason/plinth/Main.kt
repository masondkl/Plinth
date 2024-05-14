package me.mason.plinth

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.EOFException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private suspend fun <T> Connection.read(
    buffer: ByteBuffer,
    size: Int,
    method: ByteBuffer.() -> (T)
): T {
    if (buffer.position() - readMark >= size) {
        val before = buffer.position()
        buffer.position(readMark)
        val result = buffer.method()
        readMark = buffer.position()
        buffer.position(before)
        return result
    } else {
        if (buffer.remaining() < size) {
            buffer.position(readMark)
            buffer.compact()
            readMark = 0
        }
        while(buffer.position() - readMark < size) {
            try {
                suspendCoroutine<Unit> {
                    socketChannel.read(buffer, null, object : CompletionHandler<Int, Void?> {
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
                close() // Probably dont want to rely on user closing
                throw throwable
            }
        }
        val before = buffer.position()
        buffer.position(readMark)
        val result = buffer.method()
        readMark = buffer.position()
        buffer.position(before)
        return result
    }
}

//TODO: make write better
private suspend fun <T> Connection.write(
    buffer: ByteBuffer,
    method: ByteBuffer.(T) -> (Unit),
    value: T
) {
    buffer.method(value)
    buffer.flip()
    try {
        while (buffer.remaining() > 0) {
            suspendCoroutine {
                socketChannel.write(buffer, null, object : CompletionHandler<Int, Void?> {
                    override fun completed(count: Int, attachment: Void?) {
                        //TODO: handle didn't write all
                        it.resume(Unit)
                    }
                    override fun failed(throwable: Throwable, attachment: Void?) {
                        it.resumeWithException(throwable)
                    }
                })
            }
        }
    } catch (throwable: Throwable) {
        close()
        buffer.clear() // Don't know if needed
        throw throwable
    }
    buffer.clear()
}

private suspend fun Connection.write(buffer: ByteBuffer) {
    try {
        while (buffer.remaining() > 0) {
            suspendCoroutine {
                socketChannel.write(buffer, null, object : CompletionHandler<Int, Void?> {
                    override fun completed(count: Int, attachment: Void?) {
                        //TODO: handle didn't write all
                        it.resume(Unit)
                    }
                    override fun failed(throwable: Throwable, attachment: Void?) {
                        it.resumeWithException(throwable)
                    }
                })
            }
        }
    } catch (throwable: Throwable) {
        close()
        throw throwable
    }
}

interface Read {
    suspend fun byte(): Byte
    suspend fun short(): Short
    suspend fun int(): Int
    suspend fun long(): Long
    suspend fun float(): Float
    suspend fun double(): Double
    suspend fun bytes(count: Int): ByteArray
    suspend fun bytes(count: Int, chars: Boolean): CharArray
}

interface Write {
    suspend fun byte(value: Byte)
    suspend fun short(value: Short)
    suspend fun int(value: Int)
    suspend fun long(value: Long)
    suspend fun float(value: Float)
    suspend fun double(value: Double)
    suspend fun bytes(value: ByteArray)
    suspend fun bytes(value: CharArray)
    suspend fun buffer(value: ByteBuffer)
}

class MeasuredWrite() : Write {
    var size = 0
    override suspend fun byte(value: Byte) {
        size++
    }
    override suspend fun short(value: Short) {
        size += 2
    }
    override suspend fun int(value: Int) {
        size += 4
    }
    override suspend fun long(value: Long) {
        size += 8
    }
    override suspend fun float(value: Float) {
        size += 4
    }
    override suspend fun double(value: Double) {
        size += 8
    }
    override suspend fun bytes(value: ByteArray) {
        size += value.size
    }

    override suspend fun bytes(value: CharArray) {
        size += value.size
    }

    override suspend fun buffer(value: ByteBuffer) {
        size += value.remaining()
    }
}

class BufferedWrite(size: Int) : Write {
    val buffer = ByteBuffer.allocateDirect(size)
    override suspend fun byte(value: Byte) {
        buffer.put(value)
    }
    override suspend fun short(value: Short) {
        buffer.putShort(value)
    }
    override suspend fun int(value: Int) {
        buffer.putInt(value)
    }
    override suspend fun long(value: Long) {
        buffer.putLong(value)
    }
    override suspend fun float(value: Float) {
        buffer.putFloat(value)
    }
    override suspend fun double(value: Double) {
        buffer.putDouble(value)
    }
    override suspend fun bytes(value: ByteArray) {
        buffer.put(value)
    }

    override suspend fun bytes(value: CharArray) {
        value.forEach { buffer.put(it.code.toByte()) }
    }

    override suspend fun buffer(value: ByteBuffer) {
        buffer.put(value)
    }
}

interface Connection : Read, Write {
    val open: AtomicBoolean
    val socketChannel: AsynchronousSocketChannel
    val channel: Channel<suspend Write.() -> (Unit)>
    var readMark: Int
    suspend fun close()
}

fun AsynchronousSocketChannel.connection(
    maxBuffer: Int = Short.MAX_VALUE.toInt(),
    group: AsynchronousChannelGroup,
    closeGroup: Boolean = true,
    server: Server? = null,
    index: Int? = null,
    connectLock: Mutex? = null,
    whenDisconnect: List<suspend (Int) -> (Unit)>? = null
): Connection {
    val channel = this
    val read = ByteBuffer.allocateDirect(maxBuffer)
    val write = ByteBuffer.allocateDirect(maxBuffer)
    val open = AtomicBoolean(true)
    val connection = object : Connection {
        override var open = open
        override val socketChannel = channel
        override val channel = Channel<suspend Write.() -> Unit>(UNLIMITED)
        override var readMark = 0
        override suspend fun close() {
            if (open.get()) {
                while(!open.compareAndSet(open.get(), false)) { delay(1) }
                withContext(Dispatchers.IO) {
                    this@connection.close()
                    channel.close()
                }
                if (closeGroup) group.shutdown()
                if (server != null && index != null && connectLock != null && whenDisconnect != null) {
                    whenDisconnect.forEach { it.invoke(index) }
                    connectLock.withLock {
                        server.active.clear(index)
                        server.connections[index] = null
                    }
                }
            }
        }
        override suspend fun byte(): Byte = read(read, 1, ByteBuffer::get)
        override suspend fun short(): Short = read(read, 2, ByteBuffer::getShort)
        override suspend fun int(): Int = read(read, 4, ByteBuffer::getInt)
        override suspend fun long(): Long = read(read, 8, ByteBuffer::getLong)
        override suspend fun float(): Float = read(read, 4, ByteBuffer::getFloat)
        override suspend fun double(): Double = read(read, 8, ByteBuffer::getDouble)
        //TODO: make this one nio operation later
        override suspend fun bytes(count: Int): ByteArray {
            val result = ByteArray(count)
            (0..<count).forEach { result[it] = byte() }
            return result
        }
        override suspend fun bytes(count: Int, chars: Boolean): CharArray {
            val result = CharArray(count)
            (0..<count).forEach { result[it] = byte().toInt().toChar() }
            return result
        }
        override suspend fun byte(value: Byte) {
            write(write, ByteBuffer::put, value)
        }
        override suspend fun short(value: Short) {
            write(write, ByteBuffer::putShort, value)
        }
        override suspend fun int(value: Int) {
            write(write, ByteBuffer::putInt, value)
        }
        override suspend fun long(value: Long) {
            write(write, ByteBuffer::putLong, value)
        }
        override suspend fun float(value: Float) {
            write(write, ByteBuffer::putFloat, value)
        }
        override suspend fun double(value: Double) {
            write(write, ByteBuffer::putDouble, value)
        }
        //TODO: make this one nio operation later
        override suspend fun bytes(value: ByteArray) {
            write(write, ByteBuffer::put, value)
        }
        override suspend fun bytes(value: CharArray) {
            value.forEach { byte(it.code.toByte()) }
        }
        override suspend fun buffer(value: ByteBuffer) {
            write(value)
        }
    }
    return connection
}

interface Server {
    val active: BitSet
    val connections: Array<Connection?>
    suspend fun cardinality(): Int
    suspend fun get(index: Int): Connection?
    suspend fun forActive(block: suspend (Int, Connection) -> (Unit))
    suspend fun onConnect(block: suspend Connection.(Int) -> (Unit))
    suspend fun onDisconnect(block: suspend (Int) -> (Unit))
}

//TODO: handle disconnecting
suspend fun server(
    address: InetSocketAddress,
    group: AsynchronousChannelGroup,
    dispatcher: CoroutineDispatcher,
    maxConnections: Int = 256,
    maxBuffer: Int = Short.MAX_VALUE.toInt(),
    block: suspend Server.() -> (Unit) = { }
): Server {
    val serverChannel = withContext(Dispatchers.IO) {
        AsynchronousServerSocketChannel.open(group).bind(address)
    }
    val connectLock = Mutex()
    val whenConnect = Collections.synchronizedList(ArrayList<suspend Connection.(Int) -> (Unit)>())
    val whenDisconnect = Collections.synchronizedList(ArrayList<suspend (Int) -> (Unit)>())
    val server = object : Server {
        //TODO: hide active somehow
        override val active = BitSet(maxConnections)
        override val connections = Array<Connection?>(maxConnections) { null }
        override suspend fun cardinality() = connectLock.withLock { active.cardinality() }
        override suspend fun get(index: Int) = connectLock.withLock { if (active[index]) connections[index] else null }
        override suspend fun forActive(block: suspend (Int, Connection) -> Unit) = connectLock.withLock {
            var idx = active.nextSetBit(0)
            while (idx != -1) {
                block(idx, connections[idx]!!)
                idx = active.nextSetBit(idx + 1)
            }
        }
        override suspend fun onConnect(block: suspend Connection.(Int) -> Unit) = whenConnect.plusAssign(block)
        override suspend fun onDisconnect(block: suspend (Int) -> Unit) = whenDisconnect.plusAssign(block)
    }
    CoroutineScope(Dispatchers.IO).launch {
        while (isActive) {
            suspendCoroutine { continuation ->
                serverChannel.accept(null, object : CompletionHandler<AsynchronousSocketChannel, Void?> {
                    override fun completed(channel: AsynchronousSocketChannel, attachment: Void?) {
                        continuation.resume(channel)
                    }
                    override fun failed(throwable: Throwable, attachment: Void?) {
                        continuation.resume(null)
                    }
                })
            }?.also { channel ->
                CoroutineScope(dispatcher).launch {
                    val (id, connection) = connectLock.withLock {
                        val id = server.active.nextClearBit(0).also {
                            server.active.set(it)
                        }
                        val connection = channel.connection(maxBuffer, group, false, server, id, connectLock, whenDisconnect)
                        server.connections[id] = connection
                        id to connection
                    }
                    whenConnect.forEach { connection.it(id) }
                }
            }
        }
    }
    return server.apply { block() }
}

suspend fun client(
    address: InetSocketAddress,
    group: AsynchronousChannelGroup,
    closeGroup: Boolean = true,
    maxBuffer: Int = Short.MAX_VALUE.toInt(),
    block: suspend Connection.() -> (Unit)
): Connection {
    val channel = withContext(Dispatchers.IO) {
        AsynchronousSocketChannel.open(group).also {
            it.connect(address, null, object : CompletionHandler<Void?, Void?> {
                override fun completed(result: Void?, attachment: Void?) {
                }
                override fun failed(throwable: Throwable, attachment: Void?) {
                }
            })
        }
    }
    return channel.connection(maxBuffer, group, closeGroup).apply { block() }
}