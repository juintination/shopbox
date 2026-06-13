package com.example.shopbox.outbox.entity

import io.hypersistence.utils.hibernate.id.Tsid
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "outbox_events")
class OutboxEvent(
    @Id
    @Tsid
    @Column(columnDefinition = "BIGINT UNSIGNED", nullable = false)
    val id: Long? = null,

    @Column(name = "aggregate_type", nullable = false, length = 100)
    val aggregateType: String,

    @Column(name = "aggregate_id", nullable = false)
    val aggregateId: Long,

    @Column(name = "event_type", nullable = false, length = 100)
    val eventType: String,

    @Column(name = "payload", nullable = false, columnDefinition = "JSON")
    val payload: String,

    @Column(name = "processed_at")
    var processedAt: LocalDateTime? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,
)
