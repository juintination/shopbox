package com.example.shopbox.inventory.service

import com.example.shopbox.inbox.repository.InboxEventRepository
import com.example.shopbox.inventory.entity.Inventory
import com.example.shopbox.inventory.event.StockReservedEvent
import com.example.shopbox.payment.event.PaymentCompletedEvent
import com.example.shopbox.inventory.repository.InventoryRepository
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
class InventoryService(
    private val inventoryRepository: InventoryRepository,
    private val inboxEventRepository: InboxEventRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {

    private val log = KotlinLogging.logger {}

    @KafkaListener(topics = [PaymentCompletedEvent.TOPIC], groupId = "shopbox-inventory-consumer")
    fun onPaymentCompleted(
        @Header("kafka_receivedMessageKey") messageId: String,
        @Payload payload: String,
    ) {
        processPaymentCompleted(
            messageId = messageId,
            payload = payload,
        )
    }

    @Transactional
    fun processPaymentCompleted(
        messageId: String,
        payload: String,
    ) {
        if (!inboxEventRepository.saveIfAbsent(messageId)) {
            log.info { "Duplicate message skipped: $messageId" }
            return
        }

        val node = objectMapper.readTree(payload)
        val orderId = node.get("orderId").asLong()

        val inventory = inventoryRepository.save(
            Inventory.create(orderId = orderId)
        )

        val event = StockReservedEvent(
            inventoryId = inventory.id!!,
            orderId = orderId,
        )

        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = StockReservedEvent.AGGREGATE_TYPE,
                aggregateId = inventory.id,
                eventType = event.eventType,
                payload = objectMapper.writeValueAsString(event),
            )
        )
    }
}
