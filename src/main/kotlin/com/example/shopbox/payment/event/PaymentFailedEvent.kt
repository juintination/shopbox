package com.example.shopbox.payment.event

import com.example.shopbox.common.event.CompensationEvent
import io.hypersistence.tsid.TSID
import java.time.Instant

data class PaymentFailedEvent(
    override val eventId: String = TSID.fast().toString(),
    override val eventType: String = EVENT_TYPE,
    override val occurredAt: String = Instant.now().toString(),
    override val reason: String,
    val paymentId: Long,
    val orderId: Long,
) : CompensationEvent {
    companion object {
        const val EVENT_TYPE = "PaymentFailed"
        const val AGGREGATE_TYPE = "Payment"
        const val TOPIC = "payment-events"
    }
}
