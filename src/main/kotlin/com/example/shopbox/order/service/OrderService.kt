package com.example.shopbox.order.service

import com.example.shopbox.common.exception.BusinessException
import com.example.shopbox.delivery.event.DeliveryStartedEvent
import com.example.shopbox.inbox.repository.InboxEventRepository
import com.example.shopbox.order.dto.response.OrderResponse
import com.example.shopbox.order.entity.enums.OrderStatus
import com.example.shopbox.order.event.OrderCancelledEvent
import com.example.shopbox.order.event.OrderCreatedEvent
import com.example.shopbox.order.repository.OrderRepository
import com.example.shopbox.order.service.strategy.OrderLockStrategy
import com.example.shopbox.outbox.entity.OutboxEvent
import com.example.shopbox.outbox.repository.OutboxEventRepository
import com.example.shopbox.payment.event.PaymentFailedEvent
import com.example.shopbox.payment.event.PaymentRefundedEvent
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OrderService(
    private val orderLockStrategy: OrderLockStrategy,
    private val orderRepository: OrderRepository,
    private val inboxEventRepository: InboxEventRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {

    private val log = KotlinLogging.logger {}

    @KafkaListener(
        topics = [DeliveryStartedEvent.TOPIC],
        groupId = "shopbox-order-delivery-consumer",
    )
    fun onDeliveryStarted(
        @Header("kafka_receivedMessageKey") messageId: String,
        @Payload payload: String,
    ) {
        processDeliveryStarted(
            messageId = messageId,
            payload = payload,
        )
    }

    @KafkaListener(
        topics = [PaymentFailedEvent.TOPIC],
        groupId = "shopbox-order-saga-consumer",
    )
    fun onPaymentEvent(
        @Header("kafka_receivedMessageKey") messageId: String,
        @Payload payload: String,
    ) {
        processPaymentEvent(
            messageId = messageId,
            payload = payload,
        )
    }

    @Transactional
    fun createOrder(
        userId: Long,
        productId: Long,
        quantity: Int,
    ): OrderResponse {
        val order = orderLockStrategy.createOrder(
            userId = userId,
            productId = productId,
            quantity = quantity,
        )

        val event = OrderCreatedEvent(
            orderId = order.id!!,
            userId = order.userId,
            productId = order.productId,
            quantity = order.quantity,
        )

        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = OrderCreatedEvent.AGGREGATE_TYPE,
                aggregateId = order.id,
                eventType = event.eventType,
                payload = objectMapper.writeValueAsString(event),
            )
        )

        return OrderResponse.of(order)
    }

    @Transactional
    fun processDeliveryStarted(
        messageId: String,
        payload: String,
    ) {
        val node = objectMapper.readTree(payload)
        val eventType = node.get("eventType").asText()

        if (eventType != DeliveryStartedEvent.EVENT_TYPE) {
            log.info { "Unhandled delivery event type: $eventType" }
            return
        }

        if (!inboxEventRepository.saveIfAbsent(messageId)) {
            log.info { "Duplicate message skipped: $messageId" }
            return
        }

        val orderId = node.get("orderId").asLong()

        val order = orderRepository.findById(orderId).orElseThrow {
            BusinessException("주문 정보를 찾을 수 없습니다: orderId=$orderId")
        }

        order.status = OrderStatus.CONFIRMED
        orderRepository.save(order)
    }

    @Transactional
    fun processPaymentEvent(
        messageId: String,
        payload: String,
    ) {
        val node = objectMapper.readTree(payload)
        val eventType = node.get("eventType").asText()

        if (eventType != PaymentFailedEvent.EVENT_TYPE && eventType != PaymentRefundedEvent.EVENT_TYPE) {
            log.info { "Unhandled payment event type: $eventType" }
            return
        }

        if (!inboxEventRepository.saveIfAbsent(messageId)) {
            log.info { "Duplicate message skipped: $messageId" }
            return
        }

        val orderId = node.get("orderId").asLong()
        val reason = node.get("reason").asText()

        val order = orderRepository.findById(orderId).orElseThrow {
            BusinessException("주문 정보를 찾을 수 없습니다: orderId=$orderId")
        }

        order.status = OrderStatus.CANCELLED
        val saved = orderRepository.save(order)

        val event = OrderCancelledEvent(
            orderId = orderId,
            reason = reason,
        )

        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = OrderCancelledEvent.AGGREGATE_TYPE,
                aggregateId = saved.id!!,
                eventType = event.eventType,
                payload = objectMapper.writeValueAsString(event),
            )
        )
    }
}
