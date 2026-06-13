package com.example.shopbox.outbox.entity

import com.example.shopbox.common.entity.BaseEntity
import io.hypersistence.utils.hibernate.id.Tsid
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.SQLRestriction

@Entity
@Table(
    name = "dead_letter_events",
    indexes = [Index(name = "idx_dead_letter_events_deleted_at", columnList = "deleted_at")],
)
@SQLRestriction("deleted_at is null")
@SQLDelete(sql = "UPDATE dead_letter_events SET deleted_at = now() WHERE id = ?")
class DeadLetterEvent private constructor(
    @Id
    @Tsid
    @Column(columnDefinition = "BIGINT UNSIGNED", nullable = false)
    val id: Long? = null,

    @Column(name = "outbox_event_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    val outboxEventId: Long,

    @Column(name = "aggregate_type", nullable = false, length = 100)
    val aggregateType: String,

    @Column(name = "aggregate_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    val aggregateId: Long,

    @Column(name = "event_type", nullable = false, length = 100)
    val eventType: String,

    @Column(name = "payload", nullable = false, columnDefinition = "JSON")
    val payload: String,

    @Column(name = "error_message", nullable = false, length = 500)
    val errorMessage: String,
) : BaseEntity() {
    companion object {
        fun create(
            outboxEventId: Long,
            aggregateType: String,
            aggregateId: Long,
            eventType: String,
            payload: String,
            errorMessage: String,
        ) = DeadLetterEvent(
            outboxEventId = outboxEventId,
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            eventType = eventType,
            payload = payload,
            errorMessage = errorMessage,
        )
    }
}
