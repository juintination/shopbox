package com.example.shopbox.inventory.repository

import com.example.shopbox.inventory.entity.Stock
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.repository.query.Param

interface StockRepository : JpaRepository<Stock, Long> {

    fun findByProductId(
        productId: Long,
    ): Stock?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT s FROM Stock s WHERE s.productId = :productId")
    fun findByProductIdForUpdate(
        @Param("productId") productId: Long,
    ): Stock?
}
