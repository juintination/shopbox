package com.example.shopbox.payment.service

import com.example.shopbox.inbox.repository.InboxEventRepository
import com.example.shopbox.order.event.OrderCreatedEvent
import com.example.shopbox.outbox.entity.OutboxEvent
import com.example.shopbox.outbox.repository.OutboxEventRepository
import com.example.shopbox.payment.dto.response.PaymentResponse
import com.example.shopbox.payment.entity.Payment
import com.example.shopbox.payment.event.PaymentCompletedEvent
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

        val payment = paymentRepository.save(
            Payment.create(
                orderId = orderId,
                amount = 0L,
            )
        )

        val event = PaymentCompletedEvent(
            paymentId = payment.id!!,
            orderId = orderId,
            amount = payment.amount,
        )

        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = PaymentCompletedEvent.AGGREGATE_TYPE,
                aggregateId = payment.id,
                eventType = event.eventType,
                payload = objectMapper.writeValueAsString(event),
            )
        )
    }
}
