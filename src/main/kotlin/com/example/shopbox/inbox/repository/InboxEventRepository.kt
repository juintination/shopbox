package com.example.shopbox.inbox.repository

import com.example.shopbox.inbox.entity.InboxEvent
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository

interface InboxEventRepository : JpaRepository<InboxEvent, String> {
    fun saveIfAbsent(
        messageId: String,
    ) = try {
        save(InboxEvent(messageId = messageId))
        true
    } catch (e: DataIntegrityViolationException) {
        false
    }
}
