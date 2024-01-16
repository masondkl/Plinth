To create a connection, an AsynchronousChannelGroup must be created first. 

```kt
val group = withContext(Dispatchers.IO) {
    AsynchronousChannelGroup.withThreadPool(Executors.newCachedThreadPool())
}
```

A client which is able to connect to a server may be created as follows.

```kt
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

suspend fun AsynchronousChannelGroup.client(
    address: InetSocketAddress,
    maxBuffer: Int = Short.MAX_VALUE.toInt(),
    block: suspend Connection.() -> (Unit)
): Unit

group.client(address) {
    //do what you like with the connection
}
```

Servers which automatically collect connections may be created as follows.

```kt
interface Server {
    val active: BitSet
    val connections: Array<Connection>
    suspend fun forActive(block: suspend Connection.(Int) -> (Unit))
    suspend fun onConnect(block: suspend Connection.(Int) -> (Unit))
    suspend fun onDisconnect(block: suspend (Int) -> (Unit))
}

suspend fun AsynchronousChannelGroup.server(
    address: InetSocketAddress,
    maxConnections: Int = 256,
    maxBuffer: Int = Short.MAX_VALUE.toInt(),
    block: suspend Server.() -> (Unit)
): Unit

group.server(address) {
    onConnect { index -> 
        //do what you like with the connection
    }
    onDisconnect { index ->
        //cannot perform operations with the connection as it is closed here, but the index is still provided
    }
}
```

You may perform operations such as notifying other clients that a connection has been closed

```kt
server.onDisconnect { disconnected ->
    server.forActive { _ ->
        //notify
    }
}
```
