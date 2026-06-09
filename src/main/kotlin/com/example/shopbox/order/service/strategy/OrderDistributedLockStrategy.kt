package com.example.shopbox.order.service.strategy

import com.example.shopbox.common.exception.BusinessException
import com.example.shopbox.common.lock.DistributedLock
import com.example.shopbox.order.entity.Order
import com.example.shopbox.order.repository.OrderRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@ConditionalOnProperty(name = ["order.lock-strategy"], havingValue = "distributed")
class OrderDistributedLockStrategy(
    private val orderRepository: OrderRepository,
) : OrderLockStrategy {

    @DistributedLock(key = "order:{userId}:{productId}")
    @Transactional
    override fun createOrder(
        userId: Long,
        productId: Long,
        quantity: Int,
    ): Order {
        val existing = orderRepository.findByUserIdAndProductId(
            userId = userId,
            productId = productId,
        )
        if (existing != null) {
            throw BusinessException("이미 주문이 존재합니다: userId=$userId, productId=$productId")
        }

        return orderRepository.save(
            Order.create(
                userId = userId,
                productId = productId,
                quantity = quantity,
            )
        )
    }
}
