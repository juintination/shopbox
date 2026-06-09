package com.example.shopbox.inventory.service.strategy

import com.example.shopbox.common.exception.BusinessException
import com.example.shopbox.inventory.repository.StockRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@ConditionalOnProperty(name = ["inventory.lock-strategy"], havingValue = "pessimistic")
class InventoryPessimisticLockStrategy(
    private val stockRepository: StockRepository,
) : InventoryLockStrategy {

    @Transactional
    override fun deductStock(
        productId: Long,
        quantity: Int,
    ) {
        val stock = stockRepository.findByProductIdForUpdate(productId)
            ?: throw BusinessException("재고 정보를 찾을 수 없습니다: productId=$productId")
        stock.deduct(quantity)
        stockRepository.save(stock)
    }
}
