package com.example.shopbox.delivery.event

import com.example.shopbox.common.event.CompensationEvent
import io.hypersistence.tsid.TSID
import java.time.Instant

data class DeliveryFailedEvent(
    override val eventId: String = TSID.fast().toString(),
    override val eventType: String = EVENT_TYPE,
    override val occurredAt: String = Instant.now().toString(),
    override val reason: String,
    val deliveryId: Long,
    val orderId: Long,
) : CompensationEvent {
    companion object {
        const val EVENT_TYPE = "DeliveryFailed"
        const val AGGREGATE_TYPE = "Delivery"
        const val TOPIC = "delivery-events"
    }
}
