package com.example.shopbox.outbox.controller

import com.example.shopbox.common.dto.response.ApiResponse
import com.example.shopbox.outbox.dto.response.DeadLetterResponse
import com.example.shopbox.outbox.service.DeadLetterService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/dead-letters")
class DeadLetterController(
    private val deadLetterService: DeadLetterService,
) {
    @GetMapping
    fun findAll(): ResponseEntity<ApiResponse<List<DeadLetterResponse>>> {
        return ResponseEntity.ok(ApiResponse.ok(deadLetterService.findAll()))
    }

    @PostMapping("/{id}/retry")
    fun retry(
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse<DeadLetterResponse>> {
        return ResponseEntity.ok(ApiResponse.ok(deadLetterService.retry(id)))
    }
}
