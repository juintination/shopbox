package com.example.shopbox.order.service.strategy

import com.example.shopbox.order.entity.Order

interface OrderLockStrategy {
    fun createOrder(
        userId: Long,
        productId: Long,
        quantity: Int,
    ): Order
}
