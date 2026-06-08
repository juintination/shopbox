package com.example.shopbox.payment.dto.response

import com.example.shopbox.payment.entity.Payment
import com.example.shopbox.payment.entity.enums.PaymentStatus

data class PaymentResponse(
    val id: Long,
    val orderId: Long,
    val amount: Long,
    val status: PaymentStatus,
) {
    companion object {
        fun of(
            payment: Payment,
        ) = PaymentResponse(
            id = payment.id!!,
            orderId = payment.orderId,
            amount = payment.amount,
            status = payment.status,
        )
    }
}
