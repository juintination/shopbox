package com.example.shopbox.delivery.service

import com.example.shopbox.delivery.entity.Delivery
import com.example.shopbox.delivery.event.DeliveryStartedEvent
import com.example.shopbox.delivery.repository.DeliveryRepository
import com.example.shopbox.inbox.repository.InboxEventRepository
import com.example.shopbox.inventory.event.StockReservedEvent
import com.example.shopbox.outbox.entity.OutboxEvent
import com.example.shopbox.outbox.repository.OutboxEventRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DeliveryService(
    private val deliveryRepository: DeliveryRepository,
    private val inboxEventRepository: InboxEventRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {

    private val log = KotlinLogging.logger {}

    @KafkaListener(topics = [StockReservedEvent.TOPIC], groupId = "shopbox-delivery-consumer")
    fun onStockReserved(
        @Header("kafka_receivedMessageKey") messageId: String,
        @Payload payload: String,
    ) {
        processStockReserved(
            messageId = messageId,
            payload = payload,
        )
    }

    @Transactional
    fun processStockReserved(
        messageId: String,
        payload: String,
    ) {
        if (!inboxEventRepository.saveIfAbsent(messageId)) {
            log.info { "Duplicate message skipped: $messageId" }
            return
        }

        val node = objectMapper.readTree(payload)
        val orderId = node.get("orderId").asLong()

        val delivery = deliveryRepository.save(
            Delivery.create(orderId = orderId)
        )

        val event = DeliveryStartedEvent(
            deliveryId = delivery.id!!,
            orderId = orderId,
        )

        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = DeliveryStartedEvent.AGGREGATE_TYPE,
                aggregateId = delivery.id,
                eventType = event.eventType,
                payload = objectMapper.writeValueAsString(event),
            )
        )
    }
}
