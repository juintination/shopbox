package com.example.shopbox.inventory.service.strategy

interface InventoryLockStrategy {
    fun deductStock(
        productId: Long,
        quantity: Int,
    )
}
