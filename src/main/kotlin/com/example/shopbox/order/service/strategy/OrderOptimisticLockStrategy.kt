package com.example.shopbox.order.service.strategy

import com.example.shopbox.order.entity.Order
import com.example.shopbox.order.repository.OrderRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class OrderOptimisticLockStrategy(
    private val orderRepository: OrderRepository,
) : OrderLockStrategy {

    @Transactional
    override fun createOrder(
        userId: Long,
        productId: Long,
        quantity: Int,
    ): Order = orderRepository.save(
        Order.create(
            userId = userId,
            productId = productId,
            quantity = quantity,
        )
    )
}
