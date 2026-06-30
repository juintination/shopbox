package com.example.shopbox.inventory.event

import com.example.shopbox.common.event.CompensationEvent
import io.hypersistence.tsid.TSID
import java.time.Instant

data class StockReservationFailedEvent(
    override val eventId: String = TSID.fast().toString(),
    override val eventType: String = EVENT_TYPE,
    override val occurredAt: String = Instant.now().toString(),
    override val reason: String,
    val orderId: Long,
    val productId: Long,
    val quantity: Int,
) : CompensationEvent {
    companion object {
        const val EVENT_TYPE = "StockReservationFailed"
        const val AGGREGATE_TYPE = "Inventory"
        const val TOPIC = "inventory-events"
    }
}
