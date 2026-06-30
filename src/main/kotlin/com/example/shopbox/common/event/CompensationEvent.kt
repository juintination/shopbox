package com.example.shopbox.common.event

interface CompensationEvent : DomainEvent {
    val reason: String
}
