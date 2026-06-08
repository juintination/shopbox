package com.example.shopbox.payment.entity

import com.example.shopbox.common.entity.BaseEntity
import com.example.shopbox.payment.entity.enums.PaymentStatus
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
    name = "payments",
    indexes = [Index(name = "idx_payments_deleted_at", columnList = "deleted_at")],
)
@SQLRestriction("deleted_at is null")
@SQLDelete(sql = "UPDATE payments SET deleted_at = now() WHERE id = ?")
class Payment private constructor(
    @Id
    @Tsid
    @Column(columnDefinition = "BIGINT UNSIGNED", nullable = false)
    val id: Long? = null,

    @Column(name = "order_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    val orderId: Long,

    @Column(name = "amount", nullable = false)
    val amount: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: PaymentStatus = PaymentStatus.PENDING,
) : BaseEntity() {
    companion object {
        fun create(
            orderId: Long,
            amount: Long,
        ) = Payment(
            orderId = orderId,
            amount = amount,
        )
    }
}
