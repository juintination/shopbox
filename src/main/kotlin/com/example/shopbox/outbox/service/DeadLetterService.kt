package com.example.shopbox.outbox.service

import com.example.shopbox.common.exception.BusinessException
import com.example.shopbox.outbox.dto.response.DeadLetterResponse
import com.example.shopbox.outbox.entity.OutboxEvent
import com.example.shopbox.outbox.repository.DeadLetterEventRepository
import com.example.shopbox.outbox.repository.OutboxEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class DeadLetterService(
    private val deadLetterEventRepository: DeadLetterEventRepository,
    private val outboxEventRepository: OutboxEventRepository,
) {
    fun findAll(): List<DeadLetterResponse> =
        deadLetterEventRepository.findAll().map(DeadLetterResponse::from)

    @Transactional
    fun retry(
        id: Long,
    ): DeadLetterResponse {
        val dlqEvent = deadLetterEventRepository.findById(id)
            .orElseThrow { BusinessException("DLQ 이벤트를 찾을 수 없습니다: id=$id") }

        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = dlqEvent.aggregateType,
                aggregateId = dlqEvent.aggregateId,
                eventType = dlqEvent.eventType,
                payload = dlqEvent.payload,
            )
        )

        dlqEvent.deletedAt = LocalDateTime.now()
        deadLetterEventRepository.save(dlqEvent)

        return DeadLetterResponse.from(dlqEvent)
    }
}
