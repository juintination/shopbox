package com.example.shopbox.order.service.strategy

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration
class OrderLockStrategyConfig(
    @param:Value("\${order.lock-strategy}") private val strategy: String,
    private val optimistic: OrderOptimisticLockStrategy,
    private val pessimistic: OrderPessimisticLockStrategy,
    private val distributed: OrderDistributedLockStrategy,
) {
    @Bean
    @Primary
    fun orderLockStrategy(): OrderLockStrategy = when (strategy) {
        "optimistic" -> optimistic
        "pessimistic" -> pessimistic
        "distributed" -> distributed
        else -> throw IllegalArgumentException("Unknown order lock strategy: $strategy")
    }
}
