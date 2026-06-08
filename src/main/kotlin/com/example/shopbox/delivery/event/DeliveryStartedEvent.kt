package com.example.shopbox.delivery.event

import com.example.shopbox.common.event.DomainEvent
import java.time.Instant
import io.hypersistence.tsid.TSID

data class DeliveryStartedEvent(
    override val eventId: String = TSID.fast().toString(),
    override val eventType: String = EVENT_TYPE,
    override val occurredAt: String = Instant.now().toString(),
    val deliveryId: Long,
    val orderId: Long,
) : DomainEvent {
    companion object {
        const val EVENT_TYPE = "DeliveryStarted"
        const val AGGREGATE_TYPE = "Delivery"
        const val TOPIC = "delivery-events"
    }
}
