package com.opencode.acp.transport

import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.transport.BaseTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel

/**
 * Custom Transport implementation for in-process IntelliJ plugin IPC.
 * Uses coroutine channels with kotlinx.io adapters.
 *
 * The IntelliJ plugin side creates a channel pair and passes them
 * to this transport. Extends [BaseTransport] which provides
 * [onMessage], [onError], [onClose] handler registration and
 * the [state] flow.
 */
class EmbeddedTransport(
    private val scope: CoroutineScope,
    private val inputChannel: Channel<JsonRpcMessage> = Channel(Channel.UNLIMITED),
    private val outputChannel: Channel<JsonRpcMessage> = Channel(Channel.UNLIMITED)
) : BaseTransport() {

    override fun start() {
        // Channel-based transport is always ready
    }

    override fun send(message: JsonRpcMessage) {
        outputChannel.trySend(message)
    }

    /**
     * Reads the next incoming message (non-blocking).
     */
    fun tryReceive(): JsonRpcMessage? = inputChannel.tryReceive().getOrNull()

    /**
     * Feeds a message into the input channel (called by the IntelliJ plugin side).
     */
    fun feedInput(message: JsonRpcMessage) {
        inputChannel.trySend(message)
    }

    override fun close() {
        inputChannel.close()
        outputChannel.close()
        // BaseTransport manages the state transitions and fires close handlers
    }
}
