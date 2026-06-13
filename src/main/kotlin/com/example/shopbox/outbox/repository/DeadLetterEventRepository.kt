package com.example.shopbox.outbox.repository

import com.example.shopbox.outbox.entity.DeadLetterEvent
import org.springframework.data.jpa.repository.JpaRepository

interface DeadLetterEventRepository : JpaRepository<DeadLetterEvent, Long>
