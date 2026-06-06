# Data Model: Transactional Outbox / Inbox / Message Relay

**Branch**: `001-outbox-inbox-relay` | **Phase**: 1

## Entity Overview

| Entity       | Package                 | Table             | BaseEntity | Soft Delete |
|--------------|-------------------------|-------------------|------------|-------------|
| Order        | order/entity/           | orders            | ✅          | ✅           |
| Payment      | payment/entity/         | payments          | ✅          | ✅           |
| Inventory    | inventory/entity/       | inventories       | ✅          | ✅           |
| Delivery     | delivery/entity/        | deliveries        | ✅          | ✅           |
| OutboxEvent  | outbox/entity/          | outbox_events     | ❌          | ❌           |
| InboxEvent   | inbox/entity/           | inbox_events      | ❌          | ❌           |
| BaseEntity   | common/entity/          | (abstract)        | —          | —           |

---

## BaseEntity

```kotlin
// common/entity/BaseEntity.kt
@MappedSuperclass
abstract class BaseEntity {
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null
}
```

---

## OutboxEvent

Append-only. Does NOT extend `BaseEntity` — `@SQLRestriction` would filter out
processed events and break the relay query.

```kotlin
// outbox/entity/OutboxEvent.kt
@Entity
@Table(name = "outbox_events")
class OutboxEvent(
    @Id
    @Tsid
    @Column(columnDefinition = "BIGINT UNSIGNED", nullable = false)
    val id: Long? = null,

    @Column(name = "aggregate_type", nullable = false, length = 100)
    val aggregateType: String,           // e.g. "Order"

    @Column(name = "aggregate_id", nullable = false)
    val aggregateId: Long,               // FK value of the source entity

    @Column(name = "event_type", nullable = false, length = 100)
    val eventType: String,               // e.g. "OrderCreated"

    @Column(name = "payload", nullable = false, columnDefinition = "JSON")
    val payload: String,                 // serialised DomainEvent JSON

    @Column(name = "processed_at")
    var processedAt: LocalDateTime? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
```

**Relay query**: `SELECT * FROM outbox_events WHERE processed_at IS NULL`

**State transitions**:
- `processed_at = null` → pending
- `processed_at = <timestamp>` → published to Kafka

---

## InboxEvent

Append-only. PK is `message_id` (String). DB-level PK uniqueness is the idempotency gate.

```kotlin
// inbox/entity/InboxEvent.kt
@Entity
@Table(name = "inbox_events")
class InboxEvent(
    @Id
    @Column(name = "message_id", nullable = false, length = 200)
    val messageId: String,               // Kafka message key / eventId from payload

    @Column(name = "processed_at", nullable = false)
    val processedAt: LocalDateTime = LocalDateTime.now(),
)
```

**Idempotency rule**: attempting to `save()` a duplicate `messageId` throws
`DataIntegrityViolationException`, which the Service catches without re-executing
business logic.

---

## Order

```kotlin
// order/entity/Order.kt
@Entity
@Table(
    name = "orders",
    indexes = [Index(name = "idx_orders_deleted_at", columnList = "deleted_at")],
)
@SQLRestriction("deleted_at is null")
@SQLDelete(sql = "UPDATE orders SET deleted_at = now() WHERE id = ?")
class Order(
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
        ): Order = Order(
            userId = userId,
            productId = productId,
            quantity = quantity,
        )
    }
}
```

**OrderStatus enum** (`order/domain/enums/OrderStatus.kt`):

| Value     | Meaning                       |
|-----------|-------------------------------|
| `PENDING` | Order received, not yet paid  |
| `PAID`    | Payment confirmed             |
| `CANCELLED` | Cancelled                  |

---

## Payment

```kotlin
// payment/entity/Payment.kt
@Entity
@Table(
    name = "payments",
    indexes = [Index(name = "idx_payments_deleted_at", columnList = "deleted_at")],
)
@SQLRestriction("deleted_at is null")
@SQLDelete(sql = "UPDATE payments SET deleted_at = now() WHERE id = ?")
class Payment(
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
        ): Payment = Payment(
            orderId = orderId,
            amount = amount,
        )
    }
}
```

**PaymentStatus enum** (`payment/domain/enums/PaymentStatus.kt`):

| Value       | Meaning                            |
|-------------|------------------------------------|
| `PENDING`   | Payment triggered, not yet settled |
| `COMPLETED` | Payment settled                    |
| `FAILED`    | Payment failed                     |

---

## Inventory

Minimal implementation — demonstrates the Inbox pattern for the `payment-events` topic.

```kotlin
// inventory/entity/Inventory.kt
@Entity
@Table(
    name = "inventories",
    indexes = [Index(name = "idx_inventories_deleted_at", columnList = "deleted_at")],
)
@SQLRestriction("deleted_at is null")
@SQLDelete(sql = "UPDATE inventories SET deleted_at = now() WHERE id = ?")
class Inventory(
    @Id
    @Tsid
    @Column(columnDefinition = "BIGINT UNSIGNED", nullable = false)
    val id: Long? = null,

    @Column(name = "order_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    val orderId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: InventoryStatus = InventoryStatus.PENDING,
) : BaseEntity()
```

**InventoryStatus enum**: `PENDING`, `RESERVED`, `FAILED`

---

## Delivery

Minimal implementation — demonstrates the Inbox pattern for the `inventory-events` topic.

```kotlin
// delivery/entity/Delivery.kt
@Entity
@Table(
    name = "deliveries",
    indexes = [Index(name = "idx_deliveries_deleted_at", columnList = "deleted_at")],
)
@SQLRestriction("deleted_at is null")
@SQLDelete(sql = "UPDATE deliveries SET deleted_at = now() WHERE id = ?")
class Delivery(
    @Id
    @Tsid
    @Column(columnDefinition = "BIGINT UNSIGNED", nullable = false)
    val id: Long? = null,

    @Column(name = "order_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    val orderId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: DeliveryStatus = DeliveryStatus.PENDING,
) : BaseEntity()
```

**DeliveryStatus enum**: `PENDING`, `STARTED`, `FAILED`

---

## Entity Relationships

```
Order (1) ──── (1) Payment   [via orderId in Payment]
Order (1) ──── (1) Inventory [via orderId in Inventory]
Order (1) ──── (1) Delivery  [via orderId in Delivery]

Order (1) ──── (1) OutboxEvent  [created atomically per order; aggregateId = order.id]
Payment (1) ──── (1) OutboxEvent [created atomically per payment; aggregateId = payment.id]
Inventory (1) ──── (1) OutboxEvent [created atomically; aggregateId = inventory.id]
```

Cross-context access is **event-only** — no FK constraints across bounded-context tables.

---

## Database Schema Notes

- All BIGINT PKs: `BIGINT UNSIGNED` (TSID)
- JSON columns for `outbox_events.payload` require MySQL 5.7.8+
- `inbox_events.message_id` VARCHAR(200): Kafka message key is typically a UUID or TSID string
- `ddl-auto: update` in development; Flyway migrations recommended before production
