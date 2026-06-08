package com.example.shopbox.inventory.event

import com.example.shopbox.common.event.DomainEvent
import java.time.Instant
import io.hypersistence.tsid.TSID

data class StockReservedEvent(
    override val eventId: String = TSID.fast().toString(),
    override val eventType: String = EVENT_TYPE,
    override val occurredAt: String = Instant.now().toString(),
    val inventoryId: Long,
    val orderId: Long,
) : DomainEvent {
    companion object {
        const val EVENT_TYPE = "StockReserved"
        const val AGGREGATE_TYPE = "Inventory"
        const val TOPIC = "inventory-events"
    }
}
