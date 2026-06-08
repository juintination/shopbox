package com.example.shopbox.payment.controller

import com.example.shopbox.common.dto.response.ApiResponse
import com.example.shopbox.payment.dto.response.PaymentResponse
import com.example.shopbox.payment.service.PaymentService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/payments")
class PaymentController(
    private val paymentService: PaymentService,
) {
    @GetMapping("/{id}")
    fun getPayment(
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse<PaymentResponse>> {
        val payment = paymentService.getPayment(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(ApiResponse.ok(payment))
    }
}
