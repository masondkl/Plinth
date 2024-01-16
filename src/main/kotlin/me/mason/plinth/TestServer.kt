package me.mason.plinth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors

suspend fun main() {
    val address = InetSocketAddress("localhost", 9999)
    val group = withContext(Dispatchers.IO) {
        AsynchronousChannelGroup.withThreadPool(Executors.newCachedThreadPool())
    }
    group.server(address) {
        onConnect { index ->
            println("[$index]: connected")
            CoroutineScope(Dispatchers.Default).launch {
                suspendFixedRateTimer(7) {
                    println("[$index] ${byte()}")
                }
            }
        }
        onDisconnect { index ->
            println("[$index]: disconnected")
        }
    }
}