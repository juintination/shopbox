package com.example.shopbox.inbox.entity

import jakarta.persistence.*
import org.springframework.data.domain.Persistable
import java.time.LocalDateTime

@Entity
@Table(name = "inbox_events")
class InboxEvent(
    @Id
    @Column(name = "message_id", nullable = false, length = 200)
    val messageId: String,

    @Column(name = "processed_at", nullable = false)
    val processedAt: LocalDateTime = LocalDateTime.now(),
) : Persistable<String> {

    override fun getId() = messageId

    @Transient
    override fun isNew() = true // merge()에 의한 update를 방지하고 PK 제약 위반으로 중복 메시지를 감지하기 위해 항상 신규 엔티티로 취급
}
