package me.mason.plinth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import java.nio.channels.ClosedChannelException
import kotlin.concurrent.fixedRateTimer

suspend fun suspendFixedRateTimer(
    period: Long,
    initialDelay: Long = period,
    block: suspend () -> (Unit)
) {
    val channel = Channel<Unit>(Channel.UNLIMITED)
    fixedRateTimer(initialDelay = initialDelay, period = period) {
        channel.trySend(Unit)
    }
    CoroutineScope(Dispatchers.IO).launch {
        try { channel.consumeEach { block() } }
        catch (throwable: Throwable) { if (throwable is ClosedChannelException) println("Closing connection") }
        finally { channel.close(); channel.close() }
    }
}