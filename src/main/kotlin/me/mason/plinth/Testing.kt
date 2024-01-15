package me.mason.plinth

import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.seconds

suspend fun main() {
    val address = InetSocketAddress("localhost", 9999)
    val group = withContext(Dispatchers.IO) {
        AsynchronousChannelGroup.withThreadPool(Executors.newCachedThreadPool())
    }
    CoroutineScope(coroutineContext).launch {
        val server = group.server(address)
        server.onConnect { id ->
            println("result: ${int()}")
        }
    }
    CoroutineScope(coroutineContext).launch {
        delay(1.seconds)
        val client = group.client(address)
        client.short(0.toShort())
        delay(5.seconds)
        client.short(1.toShort())
    }
    delay(INFINITE)
}

//suspend fun main() {
//    val address = InetSocketAddress("localhost", 9999)
//    val group = withContext(Dispatchers.IO) {
//        AsynchronousChannelGroup.withThreadPool(Executors.newCachedThreadPool())
//    }
//    CoroutineScope(coroutineContext).launch {
//        val server = group.server(address, maxBuffer = 8)
//        server.onConnect { id ->
//            println("connect $id")
//            (1..6).forEach { _ ->
//                println("[$id] ${int()}")
//            }
//        }
//    }
//    (0..10).forEach { _ ->
//        CoroutineScope(coroutineContext).launch {
//            delay(1.seconds)
//            val client = group.client(address)
//            (1..6).forEach {
//                client.int(it)
//            }
//        }
//    }
//    delay(INFINITE)
//}

//suspend fun main() {
//    val address = InetSocketAddress("localhost", 9999)
//    val group = withContext(Dispatchers.IO) {
//        AsynchronousChannelGroup.withThreadPool(Executors.newCachedThreadPool())
//    }
//    CoroutineScope(coroutineContext).launch {
//        val server = group.server(address)
//        server.onConnect { id ->
//            println("connect $id")
//            println(string())
//            (1..6).forEach { _ ->
//                println("[$id] ${int()}")
//            }
//        }
//    }
//    (0..10).forEach { _ ->
//        CoroutineScope(coroutineContext).launch {
//            delay(1.seconds)
//            val client = group.client(address)
//            client.string("hello")
//            (1..6).forEach {
//                client.int(it)
//            }
//        }
//    }
//    delay(INFINITE)
//}