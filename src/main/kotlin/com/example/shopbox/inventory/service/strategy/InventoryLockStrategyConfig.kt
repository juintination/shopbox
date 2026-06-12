package com.example.shopbox.inventory.service.strategy

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration
class InventoryLockStrategyConfig(
    @param:Value("\${inventory.lock-strategy}") private val strategy: String,
    private val optimistic: InventoryOptimisticLockStrategy,
    private val pessimistic: InventoryPessimisticLockStrategy,
    private val distributed: InventoryDistributedLockStrategy,
) {
    @Bean
    @Primary
    fun inventoryLockStrategy(): InventoryLockStrategy = when (strategy) {
        "optimistic" -> optimistic
        "pessimistic" -> pessimistic
        "distributed" -> distributed
        else -> throw IllegalArgumentException("Unknown inventory lock strategy: $strategy")
    }
}
