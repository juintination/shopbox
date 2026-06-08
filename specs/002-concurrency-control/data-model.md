# Data Model: Concurrency Control

**Feature**: `002-concurrency-control`
**Date**: 2026-06-08

---

## New Entity: Stock

**Purpose**: 상품별 재고를 관리한다. 낙관적 락 구현을 위한 `@Version` 필드를 포함한다.

```kotlin
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
@SQLDelete(sql = "UPDATE stocks SET deleted_at = now() WHERE id = ?")
class Stock private constructor(
    @Id
    @Tsid
    @Column(columnDefinition = "BIGINT UNSIGNED")
    val id: Long? = null,

    @Column(name = "product_id", nullable = false, unique = true)
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
        require(quantity >= amount) { "재고 부족 — 현재: $quantity, 요청: $amount" }
        quantity -= amount
    }
}
```

**Fields**:

| Field | Type | Constraint | Notes |
|-------|------|-----------|-------|
| `id` | `Long?` | PK, TSID, BIGINT UNSIGNED | |
| `productId` | `Long` | NOT NULL, UNIQUE | 상품 ID (외래키 아님 — 컨텍스트 경계) |
| `quantity` | `Int` | NOT NULL | 현재 재고 수량; `deduct()`로만 변경 |
| `version` | `Long` | NOT NULL, DEFAULT 0 | JPA `@Version` — 낙관적 락 충돌 감지 |
| `createdAt` | `LocalDateTime` | NOT NULL | BaseEntity |
| `updatedAt` | `LocalDateTime` | NOT NULL | BaseEntity |
| `deletedAt` | `LocalDateTime?` | nullable | Soft Delete |

**State Transitions**:

```
create(productId, quantity)  →  quantity > 0
deduct(amount)               →  quantity >= 0  (quantity < amount → IllegalArgumentException)
```

---

## Modified Entity: Order

**Purpose**: 기존 주문 엔티티에 동일 사용자의 동일 상품 중복 주문을 DB 레벨에서 차단하는 복합 unique 제약을 추가한다.

**Change**: `@Table`에 `uniqueConstraints` 추가

```kotlin
@Table(
    name = "orders",
    // 기존 인덱스 유지 (deleted_at 등)
    indexes = [
        Index(name = "idx_orders_deleted_at", columnList = "deleted_at"),
        // 기존 인덱스 그대로
    ],
    uniqueConstraints = [
        UniqueConstraint(             // ← 신규 추가
            name = "uk_orders_user_product",
            columnNames = ["user_id", "product_id"],
        ),
    ],
)
```

**Notes**:
- 이 제약은 세 전략 모두의 최후 방어선으로 동작한다.
- OptimisticLock/DistributedLock은 check-then-insert 실패 시 `DataIntegrityViolationException`을 catch한다.
- PessimisticLock은 `SELECT FOR UPDATE`로 사전 차단하되 제약이 후방어 역할을 한다.
- `JPA DDL Auto`가 `validate`/`none`인 경우 마이그레이션 스크립트로 제약을 별도 추가해야 한다.

---

## Repository: StockRepository

```kotlin
interface StockRepository : JpaRepository<Stock, Long> {

    fun findByProductId(
        productId: Long,
    ): Stock?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(
        QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"),
    )
    @Query("SELECT s FROM Stock s WHERE s.productId = :productId")
    fun findByProductIdForUpdate(
        @Param("productId") productId: Long,
    ): Stock?
}
```

---

## Configuration Schema

```yaml
# src/main/resources/application.yml 추가

order:
  lock-strategy: optimistic   # optimistic | pessimistic | distributed

inventory:
  lock-strategy: optimistic   # optimistic | pessimistic | distributed

spring:
  data:
    redis:
      host: localhost
      port: 6379
```
