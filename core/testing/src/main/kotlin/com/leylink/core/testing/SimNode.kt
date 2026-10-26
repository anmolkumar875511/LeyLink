package com.leylink.core.testing

import com.leylink.core.model.DeliveredMessage
import com.leylink.core.model.NodeId
import com.leylink.core.router.MeshRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** One simulated phone: its router, stores and the messages its user has seen. */
class SimNode(
    val id: NodeId,
    val transport: FakeTransport,
    val outbox: InMemoryOutboxStore,
    val router: MeshRouter,
) {
    val delivered = mutableListOf<DeliveredMessage>()

    /** Starts the router and records everything shown to this node's user. */
    fun start(scope: CoroutineScope) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            router.deliveries.collect { delivered += it }
        }
        router.start(scope)
    }
}
