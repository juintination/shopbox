package com.example.shopbox.order.event

import com.example.shopbox.common.event.DomainEvent
import java.time.Instant
import io.hypersistence.tsid.TSID

data class OrderCreatedEvent(
    override val eventId: String = TSID.fast().toString(),
    override val eventType: String = EVENT_TYPE,
    override val occurredAt: String = Instant.now().toString(),
    val orderId: Long,
    val userId: Long,
    val productId: Long,
    val quantity: Int,
) : DomainEvent {
    companion object {
        const val EVENT_TYPE = "OrderCreated"
        const val AGGREGATE_TYPE = "Order"
        const val TOPIC = "order-events"
    }
}
