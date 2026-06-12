package com.example.shopbox.inventory.service.strategy

import com.example.shopbox.common.exception.BusinessException
import com.example.shopbox.inventory.repository.StockRepository
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Component
class InventoryOptimisticLockStrategy(
    private val stockRepository: StockRepository,
    private val transactionManager: PlatformTransactionManager,
) : InventoryLockStrategy {

    override fun deductStock(
        productId: Long,
        quantity: Int,
    ) {
        val template = TransactionTemplate(transactionManager)
        for (attempt in 0 until MAX_RETRY) {
            try {
                template.execute {
                    val stock = stockRepository.findByProductId(productId)
                        ?: throw BusinessException("재고 정보를 찾을 수 없습니다: productId=$productId")
                    stock.deduct(quantity)
                    stockRepository.saveAndFlush(stock)
                }
                return
            } catch (e: ObjectOptimisticLockingFailureException) {
                if (attempt == MAX_RETRY - 1) throw e
                Thread.sleep(RETRY_BACKOFF_MS * (attempt + 1))
            }
        }
    }

    companion object {
        private const val MAX_RETRY = 5
        private const val RETRY_BACKOFF_MS = 50L
    }
}
