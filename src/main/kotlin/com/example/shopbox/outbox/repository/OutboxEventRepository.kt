package com.example.shopbox.outbox.repository

import com.example.shopbox.outbox.entity.OutboxEvent
import org.springframework.data.jpa.repository.JpaRepository

interface OutboxEventRepository : JpaRepository<OutboxEvent, Long> {
    fun findByProcessedAtIsNull(): List<OutboxEvent>
}
