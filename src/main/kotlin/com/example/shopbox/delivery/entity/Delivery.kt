package com.example.shopbox.delivery.entity

import com.example.shopbox.common.entity.BaseEntity
import com.example.shopbox.delivery.entity.enums.DeliveryStatus
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
    name = "deliveries",
    indexes = [Index(name = "idx_deliveries_deleted_at", columnList = "deleted_at")],
)
@SQLRestriction("deleted_at is null")
@SQLDelete(sql = "UPDATE deliveries SET deleted_at = now() WHERE id = ?")
class Delivery private constructor(
    @Id
    @Tsid
    @Column(columnDefinition = "BIGINT UNSIGNED", nullable = false)
    val id: Long? = null,

    @Column(name = "order_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    val orderId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: DeliveryStatus = DeliveryStatus.PENDING,
) : BaseEntity() {
    companion object {
        fun create(
            orderId: Long,
        ) = Delivery(
            orderId = orderId,
        )
    }
}
