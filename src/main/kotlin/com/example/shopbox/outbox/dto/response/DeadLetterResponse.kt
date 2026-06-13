package com.example.shopbox.outbox.dto.response

import com.example.shopbox.outbox.entity.DeadLetterEvent
import java.time.LocalDateTime

data class DeadLetterResponse(
    val id: Long,
    val outboxEventId: Long,
    val aggregateType: String,
    val aggregateId: Long,
    val eventType: String,
    val payload: String,
    val errorMessage: String,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(
            event: DeadLetterEvent,
        ) = DeadLetterResponse(
            id = event.id!!,
            outboxEventId = event.outboxEventId,
            aggregateType = event.aggregateType,
            aggregateId = event.aggregateId,
            eventType = event.eventType,
            payload = event.payload,
            errorMessage = event.errorMessage,
            createdAt = event.createdAt,
        )
    }
}
