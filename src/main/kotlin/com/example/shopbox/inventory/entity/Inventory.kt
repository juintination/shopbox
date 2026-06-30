package com.example.shopbox.inventory.entity

import com.example.shopbox.common.entity.BaseEntity
import com.example.shopbox.inventory.entity.enums.InventoryStatus
import io.hypersistence.utils.hibernate.id.Tsid
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.SQLRestriction

@Entity
@Table(
    name = "inventories",
    indexes = [Index(name = "idx_inventories_deleted_at", columnList = "deleted_at")],
)
@SQLRestriction("deleted_at is null")
@SQLDelete(sql = "UPDATE inventories SET deleted_at = now() WHERE id = ?")
class Inventory private constructor(
    @Id
    @Tsid
    @Column(columnDefinition = "BIGINT UNSIGNED", nullable = false)
    val id: Long? = null,

    @Column(name = "order_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    val orderId: Long,

    @Column(name = "product_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    val productId: Long,

    @Column(nullable = false)
    val quantity: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: InventoryStatus = InventoryStatus.PENDING,
) : BaseEntity() {
    companion object {
        fun create(
            orderId: Long,
            productId: Long,
            quantity: Int,
        ) = Inventory(
            orderId = orderId,
            productId = productId,
            quantity = quantity,
        )
    }
}
