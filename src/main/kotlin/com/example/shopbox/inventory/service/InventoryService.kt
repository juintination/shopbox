package com.example.shopbox.inventory.service

import com.example.shopbox.common.exception.BusinessException
import com.example.shopbox.delivery.event.DeliveryFailedEvent
import com.example.shopbox.inbox.repository.InboxEventRepository
import com.example.shopbox.inventory.entity.Inventory
import com.example.shopbox.inventory.entity.enums.InventoryStatus
import com.example.shopbox.inventory.event.StockReservationFailedEvent
import com.example.shopbox.inventory.event.StockReservedEvent
import com.example.shopbox.inventory.event.StockRestoredEvent
import com.example.shopbox.inventory.repository.InventoryRepository
import com.example.shopbox.inventory.service.strategy.InventoryLockStrategy
import com.example.shopbox.outbox.entity.OutboxEvent
import com.example.shopbox.outbox.repository.OutboxEventRepository
import com.example.shopbox.payment.event.PaymentCompletedEvent
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
    private val inventoryLockStrategy: InventoryLockStrategy,
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

    @KafkaListener(
        topics = [DeliveryFailedEvent.TOPIC],
        groupId = "shopbox-inventory-saga-consumer",
    )
    fun onDeliveryFailed(
        @Header("kafka_receivedMessageKey") messageId: String,
        @Payload payload: String,
    ) {
        processDeliveryFailed(
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
        val productId = node.get("productId").asLong()
        val quantity = node.get("quantity").asInt()

        inventoryLockStrategy.deductStock(
            productId = productId,
            quantity = quantity,
        )

        val inventory = Inventory.create(
            orderId = orderId,
            productId = productId,
            quantity = quantity,
        )
        inventory.status = InventoryStatus.RESERVED

        val saved = inventoryRepository.save(inventory)

        val event = StockReservedEvent(
            inventoryId = saved.id!!,
            orderId = orderId,
        )

        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = StockReservedEvent.AGGREGATE_TYPE,
                aggregateId = saved.id,
                eventType = event.eventType,
                payload = objectMapper.writeValueAsString(event),
            )
        )
    }

    @Transactional
    fun processStockReservationFailed(
        messageId: String,
        payload: String,
    ) {
        if (!inboxEventRepository.saveIfAbsent(messageId)) {
            log.info { "Duplicate message skipped: $messageId" }
            return
        }

        val node = objectMapper.readTree(payload)
        val orderId = node.get("orderId").asLong()
        val productId = node.get("productId").asLong()
        val quantity = node.get("quantity").asInt()
        val reason = node.get("reason").asText()

        val inventory = Inventory.create(
            orderId = orderId,
            productId = productId,
            quantity = quantity,
        )
        inventory.status = InventoryStatus.FAILED

        val saved = inventoryRepository.save(inventory)

        val event = StockReservationFailedEvent(
            orderId = orderId,
            productId = productId,
            quantity = quantity,
            reason = reason,
        )

        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = StockReservationFailedEvent.AGGREGATE_TYPE,
                aggregateId = saved.id!!,
                eventType = event.eventType,
                payload = objectMapper.writeValueAsString(event),
            )
        )
    }

    @Transactional
    fun processDeliveryFailed(
        messageId: String,
        payload: String,
    ) {
        val node = objectMapper.readTree(payload)
        val eventType = node.get("eventType").asText()

        if (eventType != DeliveryFailedEvent.EVENT_TYPE) {
            log.info { "Unhandled delivery event type: $eventType" }
            return
        }

        if (!inboxEventRepository.saveIfAbsent(messageId)) {
            log.info { "Duplicate message skipped: $messageId" }
            return
        }

        val orderId = node.get("orderId").asLong()
        val reason = node.get("reason").asText()

        val inventory = inventoryRepository.findByOrderId(orderId)
            ?: throw BusinessException("재고 정보를 찾을 수 없습니다: orderId=$orderId")

        inventoryLockStrategy.restoreStock(
            productId = inventory.productId,
            quantity = inventory.quantity,
        )

        inventory.status = InventoryStatus.RESTORED
        val saved = inventoryRepository.save(inventory)

        val event = StockRestoredEvent(
            inventoryId = saved.id!!,
            orderId = orderId,
            productId = inventory.productId,
            quantity = inventory.quantity,
            reason = reason,
        )

        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = StockRestoredEvent.AGGREGATE_TYPE,
                aggregateId = saved.id,
                eventType = event.eventType,
                payload = objectMapper.writeValueAsString(event),
            )
        )
    }
}
