package me.mason.plinth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors

suspend fun main() {
    val address = InetSocketAddress("localhost", 9999)
    val group = withContext(Dispatchers.IO) {
        AsynchronousChannelGroup.withThreadPool(Executors.newCachedThreadPool())
    }
    group.client(address) {
        suspendFixedRateTimer(7) {
            byte(0)
        }
    }
}