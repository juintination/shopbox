package com.example.shopbox.order.controller

import com.example.shopbox.common.dto.response.ApiResponse
import com.example.shopbox.order.dto.request.CreateOrderRequest
import com.example.shopbox.order.dto.response.OrderResponse
import com.example.shopbox.order.service.OrderService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/orders")
class OrderController(
    private val orderService: OrderService,
) {
    @PostMapping
    fun createOrder(
        @Valid @RequestBody request: CreateOrderRequest,
    ): ResponseEntity<ApiResponse<OrderResponse>> {
        val order = orderService.createOrder(
            userId = request.userId!!,
            productId = request.productId!!,
            quantity = request.quantity!!,
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(order))
    }
}
