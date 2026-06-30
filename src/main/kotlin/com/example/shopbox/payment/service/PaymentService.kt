package com.example.shopbox.payment.service

import com.example.shopbox.common.exception.BusinessException
import com.example.shopbox.inbox.repository.InboxEventRepository
import com.example.shopbox.inventory.event.StockReservationFailedEvent
import com.example.shopbox.inventory.event.StockRestoredEvent
import com.example.shopbox.order.event.OrderCreatedEvent
import com.example.shopbox.outbox.entity.OutboxEvent
import com.example.shopbox.outbox.repository.OutboxEventRepository
import com.example.shopbox.payment.dto.response.PaymentResponse
import com.example.shopbox.payment.entity.Payment
import com.example.shopbox.payment.entity.enums.PaymentStatus
import com.example.shopbox.payment.event.PaymentCompletedEvent
import com.example.shopbox.payment.event.PaymentFailedEvent
import com.example.shopbox.payment.event.PaymentRefundedEvent
import com.example.shopbox.payment.repository.PaymentRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val inboxEventRepository: InboxEventRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {

    private val log = KotlinLogging.logger {}

    @KafkaListener(topics = [OrderCreatedEvent.TOPIC], groupId = "shopbox-payment-consumer")
    fun onOrderCreated(
        @Header("kafka_receivedMessageKey") messageId: String,
        @Payload payload: String,
    ) {
        processOrderCreated(
            messageId = messageId,
            payload = payload,
        )
    }

    @KafkaListener(
        topics = [StockReservationFailedEvent.TOPIC],
        groupId = "shopbox-payment-saga-consumer",
    )
    fun onInventoryEvent(
        @Header("kafka_receivedMessageKey") messageId: String,
        @Payload payload: String,
    ) {
        processInventoryEvent(
            messageId = messageId,
            payload = payload,
        )
    }

    @Transactional(readOnly = true)
    fun getPayment(
        id: Long,
    ): PaymentResponse? = paymentRepository.findById(id).orElse(null)?.let { PaymentResponse.of(it) }

    @Transactional
    fun processOrderCreated(
        messageId: String,
        payload: String,
    ) {
        if (!inboxEventRepository.saveIfAbsent(messageId)) {
            log.info { "Duplicate message skipped: $messageId" }
            return
        }

        val node = objectMapper.readTree(payload)
        val orderId = node.get("orderId").asLong()
        val productId = node.get("productId")?.asLong() ?: 0L
        val quantity = node.get("quantity")?.asInt() ?: 0

        val payment = Payment.create(
            orderId = orderId,
            amount = 0L,
        )
        payment.status = PaymentStatus.COMPLETED

        val saved = paymentRepository.save(payment)

        val event = PaymentCompletedEvent(
            paymentId = saved.id!!,
            orderId = orderId,
            amount = saved.amount,
            productId = productId,
            quantity = quantity,
        )

        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = PaymentCompletedEvent.AGGREGATE_TYPE,
                aggregateId = saved.id,
                eventType = event.eventType,
                payload = objectMapper.writeValueAsString(event),
            )
        )
    }

    @Transactional
    fun processPaymentFailed(
        messageId: String,
        payload: String,
    ) {
        if (!inboxEventRepository.saveIfAbsent(messageId)) {
            log.info { "Duplicate message skipped: $messageId" }
            return
        }

        val node = objectMapper.readTree(payload)
        val orderId = node.get("orderId").asLong()
        val reason = node.get("reason").asText()

        val payment = Payment.create(
            orderId = orderId,
            amount = 0L,
        )
        payment.status = PaymentStatus.FAILED

        val saved = paymentRepository.save(payment)

        val event = PaymentFailedEvent(
            paymentId = saved.id!!,
            orderId = orderId,
            reason = reason,
        )

        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = PaymentFailedEvent.AGGREGATE_TYPE,
                aggregateId = saved.id,
                eventType = event.eventType,
                payload = objectMapper.writeValueAsString(event),
            )
        )
    }

    @Transactional
    fun processInventoryEvent(
        messageId: String,
        payload: String,
    ) {
        val node = objectMapper.readTree(payload)
        val eventType = node.get("eventType").asText()

        if (eventType != StockReservationFailedEvent.EVENT_TYPE && eventType != StockRestoredEvent.EVENT_TYPE) {
            log.info { "Unhandled inventory event type: $eventType" }
            return
        }

        if (!inboxEventRepository.saveIfAbsent(messageId)) {
            log.info { "Duplicate message skipped: $messageId" }
            return
        }

        val orderId = node.get("orderId").asLong()
        val reason = node.get("reason").asText()

        val payment = paymentRepository.findByOrderId(orderId)
            ?: throw BusinessException("결제 정보를 찾을 수 없습니다: orderId=$orderId")

        payment.status = PaymentStatus.REFUNDED
        val saved = paymentRepository.save(payment)

        val event = PaymentRefundedEvent(
            paymentId = saved.id!!,
            orderId = orderId,
            reason = reason,
        )

        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = PaymentRefundedEvent.AGGREGATE_TYPE,
                aggregateId = saved.id,
                eventType = event.eventType,
                payload = objectMapper.writeValueAsString(event),
            )
        )
    }
}
