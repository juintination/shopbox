package com.example.shopbox.payment.event

import com.example.shopbox.common.event.DomainEvent
import io.hypersistence.tsid.TSID
import java.time.Instant

data class PaymentCompletedEvent(
    override val eventId: String = TSID.fast().toString(),
    override val eventType: String = EVENT_TYPE,
    override val occurredAt: String = Instant.now().toString(),
    val paymentId: Long,
    val orderId: Long,
    val amount: Long,
) : DomainEvent {
    companion object {
        const val EVENT_TYPE = "PaymentCompleted"
        const val AGGREGATE_TYPE = "Payment"
        const val TOPIC = "payment-events"
    }
}
