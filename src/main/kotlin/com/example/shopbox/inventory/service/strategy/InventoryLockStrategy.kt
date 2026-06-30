package com.example.shopbox.inventory.service.strategy

interface InventoryLockStrategy {
    fun deductStock(
        productId: Long,
        quantity: Int,
    )

    fun restoreStock(
        productId: Long,
        quantity: Int,
    )
}
