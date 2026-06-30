package com.example.shopbox.inventory.entity

import com.example.shopbox.common.entity.BaseEntity
import io.hypersistence.utils.hibernate.id.Tsid
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.SQLRestriction

@Entity
@Table(
    name = "stocks",
    indexes = [
        Index(name = "idx_stocks_product_id", columnList = "product_id"),
        Index(name = "idx_stocks_deleted_at", columnList = "deleted_at"),
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_stocks_product_id", columnNames = ["product_id"]),
    ],
)
@SQLRestriction("deleted_at is null")
@SQLDelete(sql = "UPDATE stocks SET deleted_at = now() WHERE id = ? AND version = ?")
class Stock private constructor(
    @Id
    @Tsid
    @Column(columnDefinition = "BIGINT UNSIGNED", nullable = false)
    val id: Long? = null,

    @Column(name = "product_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    val productId: Long,

    @Column(nullable = false)
    var quantity: Int,

    @Version
    @Column(nullable = false)
    val version: Long = 0,
) : BaseEntity() {

    companion object {
        fun create(
            productId: Long,
            quantity: Int,
        ) = Stock(
            productId = productId,
            quantity = quantity,
        )
    }

    fun deduct(
        amount: Int,
    ) {
        if (quantity < amount) {
            throw IllegalArgumentException("Insufficient stock: current=$quantity, requested=$amount")
        }
        quantity -= amount
    }

    fun restore(
        amount: Int,
    ) {
        quantity += amount
    }
}
