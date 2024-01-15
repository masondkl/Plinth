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

val connection = group.client(address)
//do what you like with the connection
```

Servers which automatically collect connections may be created as follows.

```kt
interface Server {
    val active: Int
    val connections: Array<Connection>
    suspend fun onConnect(block: suspend Connection.(Int) -> (Unit))
}

val server = group.server(address)
server.onConnect { index ->
    //do what you like with the connection
}
```
