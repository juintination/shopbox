package com.example.shopbox.common.event

interface DomainEvent {
    val eventId: String
    val eventType: String
    val occurredAt: String
}
