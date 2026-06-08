package com.example.shopbox.order.service

import com.example.shopbox.order.dto.response.OrderResponse
import com.example.shopbox.order.entity.Order
import com.example.shopbox.order.event.OrderCreatedEvent
import com.example.shopbox.order.repository.OrderRepository
import com.example.shopbox.outbox.entity.OutboxEvent
import com.example.shopbox.outbox.repository.OutboxEventRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun createOrder(
        userId: Long,
        productId: Long,
        quantity: Int,
    ): OrderResponse {
        val order = orderRepository.save(
            Order.create(
                userId = userId,
                productId = productId,
                quantity = quantity,
            )
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
}
