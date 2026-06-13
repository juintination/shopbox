package com.example.shopbox.outbox.relay

import com.example.shopbox.delivery.event.DeliveryStartedEvent
import com.example.shopbox.inventory.event.StockReservedEvent
import com.example.shopbox.order.event.OrderCreatedEvent
import com.example.shopbox.outbox.entity.DeadLetterEvent
import com.example.shopbox.outbox.repository.DeadLetterEventRepository
import com.example.shopbox.outbox.repository.OutboxEventRepository
import com.example.shopbox.payment.event.PaymentCompletedEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class MessageRelay(
    private val outboxEventRepository: OutboxEventRepository,
    private val deadLetterEventRepository: DeadLetterEventRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @param:Value("\${outbox.relay.max-retry:5}") private val maxRetry: Int,
) {

    private val log = KotlinLogging.logger {}

    @Scheduled(fixedDelayString = "\${outbox.relay.interval:5000}")
    fun relay() {
        val pendingEvents = outboxEventRepository.findByProcessedAtIsNullAndRetryCountLessThan(maxRetry)
        if (pendingEvents.isEmpty()) return

        pendingEvents.forEach { event ->
            try {
                val topic = topicFor(event.aggregateType)
                kafkaTemplate.send(topic, event.id.toString(), event.payload).get()
                event.processedAt = LocalDateTime.now()
                outboxEventRepository.save(event)
            } catch (e: Exception) {
                log.warn { "Failed to publish outbox event ${event.id} (retryCount=${event.retryCount}): ${e.message}" }
                event.retryCount++
                if (event.retryCount >= maxRetry) {
                    deadLetterEventRepository.save(
                        DeadLetterEvent.create(
                            outboxEventId = event.id!!,
                            aggregateType = event.aggregateType,
                            aggregateId = event.aggregateId,
                            eventType = event.eventType,
                            payload = event.payload,
                            errorMessage = e.message ?: "Unknown error",
                        )
                    )
                    event.processedAt = LocalDateTime.now()
                    log.warn { "Moved outbox event ${event.id} to dead letter queue after $maxRetry retries" }
                }
                outboxEventRepository.save(event)
            }
        }
    }

    private fun topicFor(
        aggregateType: String,
    ) = when (aggregateType) {
        OrderCreatedEvent.AGGREGATE_TYPE -> OrderCreatedEvent.TOPIC
        PaymentCompletedEvent.AGGREGATE_TYPE -> PaymentCompletedEvent.TOPIC
        StockReservedEvent.AGGREGATE_TYPE -> StockReservedEvent.TOPIC
        DeliveryStartedEvent.AGGREGATE_TYPE -> DeliveryStartedEvent.TOPIC
        else -> "$aggregateType-events".lowercase()
    }
}
