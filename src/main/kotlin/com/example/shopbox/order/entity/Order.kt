package com.example.shopbox.order.entity

import com.example.shopbox.common.entity.BaseEntity
import com.example.shopbox.order.entity.enums.OrderStatus
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
    name = "orders",
    indexes = [Index(name = "idx_orders_deleted_at", columnList = "deleted_at")],
)
@SQLRestriction("deleted_at is null")
@SQLDelete(sql = "UPDATE orders SET deleted_at = now() WHERE id = ?")
class Order private constructor(
    @Id
    @Tsid
    @Column(columnDefinition = "BIGINT UNSIGNED", nullable = false)
    val id: Long? = null,

    @Column(name = "user_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    val userId: Long,

    @Column(name = "product_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    val productId: Long,

    @Column(name = "quantity", nullable = false)
    val quantity: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: OrderStatus = OrderStatus.PENDING,
) : BaseEntity() {
    companion object {
        fun create(
            userId: Long,
            productId: Long,
            quantity: Int,
        ) = Order(
            userId = userId,
            productId = productId,
            quantity = quantity,
        )
    }
}
