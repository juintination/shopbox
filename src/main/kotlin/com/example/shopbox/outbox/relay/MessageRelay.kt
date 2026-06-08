package com.example.shopbox.outbox.relay

import com.example.shopbox.delivery.event.DeliveryStartedEvent
import com.example.shopbox.inventory.event.StockReservedEvent
import com.example.shopbox.order.event.OrderCreatedEvent
import com.example.shopbox.outbox.repository.OutboxEventRepository
import com.example.shopbox.payment.event.PaymentCompletedEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class MessageRelay(
    private val outboxEventRepository: OutboxEventRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {

    private val log = KotlinLogging.logger {}

    @Scheduled(fixedDelayString = "\${outbox.relay.interval:5000}")
    fun relay() {
        val pendingEvents = outboxEventRepository.findByProcessedAtIsNull()
        if (pendingEvents.isEmpty()) return

        pendingEvents.forEach { event ->
            try {
                val topic = topicFor(event.aggregateType)
                kafkaTemplate.send(topic, event.id.toString(), event.payload).get()
                event.processedAt = LocalDateTime.now()
                outboxEventRepository.save(event)
            } catch (e: Exception) {
                log.warn { "Failed to publish outbox event ${event.id}: ${e.message}" }
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
