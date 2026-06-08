package com.example.shopbox.order.dto.response

import com.example.shopbox.order.entity.enums.OrderStatus
import com.example.shopbox.order.entity.Order

data class OrderResponse(
    val id: Long,
    val userId: Long,
    val productId: Long,
    val quantity: Int,
    val status: OrderStatus,
) {
    companion object {
        fun of(
            order: Order,
        ) = OrderResponse(
            id = order.id!!,
            userId = order.userId,
            productId = order.productId,
            quantity = order.quantity,
            status = order.status,
        )
    }
}
