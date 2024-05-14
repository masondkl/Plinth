To create a connection, an AsynchronousChannelGroup must be created first, one may be created as follows.

```kt
val service = Executors.newCachedThreadPool()
//val service = Executors.newFixedThreadPool(n)
val group = withContext(Dispatchers.IO) {
    AsynchronousChannelGroup.withThreadPool(service)
}
```

You will also have to use this service as a coroutine dispatcher to handle collecting incoming connections on a server or when using channels to synchronize writes.

```kt
val dispatcher = service.asCoroutineDispatcher()
```

A client which is able to connect to a server may be created as follows.

```kt
import java.net.InetSocketAddress

interface Read {
    suspend fun byte(): Byte?
    suspend fun short(): Short?
    suspend fun int(): Int?
    suspend fun long(): Long?
    suspend fun float(): Float?
    suspend fun double(): Double?
    suspend fun bytes(count: Int): ByteArray?
}

interface Write {
    suspend fun byte(value: Byte)
    suspend fun short(value: Short)
    suspend fun int(value: Int)
    suspend fun long(value: Long)
    suspend fun float(value: Float)
    suspend fun double(value: Double)
    suspend fun bytes(value: ByteArray)
    suspend fun buffer(value: ByteBuffer)
}

interface Connection : Read, Write {
    val open: Boolean
    val channel: AsynchronousSocketChannel
    var readMark: Int
    suspend fun close()
}

suspend fun client(
    address: InetSocketAddress,
    group: AsynchronousChannelGroup,
    closeGroup: Boolean = true,
    maxBuffer: Int = Short.MAX_VALUE.toInt(),
    block: suspend Connection.() -> (Unit)
): Connection

val address = InetSocketAddress("0.0.0.0", 9999)
client(address, group) {
    //for real time applications it is advised to synchronize writes by using Connection's internal channel
    //handle dispatching writes from channel to the socket channel with dispatchWrites
    dispatchWrites(dispatcher)
    //write by sending or trySending to channel
    channel.trySend {
        int(0)
    }
}
```

Servers which automatically collect connections may be created as follows.

```kt
interface Server {
    val active: BitSet
    val connections: Array<Connection?>
    suspend fun forActive(block: suspend (Int, Connection) -> (Unit))
    suspend fun onConnect(block: suspend Connection.(Int) -> (Unit))
    suspend fun onDisconnect(block: suspend (Int) -> (Unit))
}

suspend fun server(
    address: InetSocketAddress,
    group: AsynchronousChannelGroup,
    dispatcher: CoroutineDispatcher,
    maxConnections: Int = 256,
    maxBuffer: Int = Short.MAX_VALUE.toInt(),
    block: suspend Server.() -> (Unit) = { }
): Server

val address = InetSocketAddress("0.0.0.0", 9999)
server(address, group, dispatcher) {
    onConnect { index ->
        //it is advised to synchronize writes by using Connection's internal channel
        //handle dispatching writes from channel to the socket channel with dispatchWrites
        dispatchWrites(dispatcher) 
        //write by sending or trySending to channel
        channel.trySend {
            int(0)
        }
    }
    onDisconnect { index ->
        //cannot perform operations with the connection as it is closed here, but the index is still provided
    }
}
```

A real example can be found on my github: [HashBruteforcer](https://github.com/masondkl/HashBruteforcer)
