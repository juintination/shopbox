# Contracts: Lock Strategy Interfaces

**Feature**: `002-concurrency-control`
**Date**: 2026-06-08

---

## OrderLockStrategy

**Location**: `order/service/strategy/OrderLockStrategy.kt`
**Purpose**: 주문 생성 동시성 제어 전략의 공통 인터페이스

```kotlin
interface OrderLockStrategy {
    fun createOrder(
        userId: Long,
        productId: Long,
        quantity: Int,
    ): Order
}
```

**Contract**:
- 동일 `(userId, productId)` 조합의 동시 호출 중 정확히 1건만 `Order`를 반환하고 나머지는 예외를 던진다.
- 성공 시 `Order` 엔티티를 반환한다 (서비스 레이어에서 DTO 변환).
- 실패 시 던지는 예외는 전략마다 다르다:

| 전략 | 실패 예외 |
|------|---------|
| Optimistic | `ObjectOptimisticLockingFailureException` (재시도 3회 초과 시) 또는 `DataIntegrityViolationException` |
| Pessimistic | `PessimisticLockingFailureException` (타임아웃) |
| Distributed | `IllegalStateException` ("분산락 획득 실패") |

**Implementations**:

| Class | Bean Activation |
|-------|----------------|
| `OrderOptimisticLockStrategy` | `order.lock-strategy: optimistic` |
| `OrderPessimisticLockStrategy` | `order.lock-strategy: pessimistic` |
| `OrderDistributedLockStrategy` | `order.lock-strategy: distributed` |

---

## InventoryLockStrategy

**Location**: `inventory/service/strategy/InventoryLockStrategy.kt`
**Purpose**: 재고 차감 동시성 제어 전략의 공통 인터페이스

```kotlin
interface InventoryLockStrategy {
    fun deductStock(
        productId: Long,
        quantity: Int,
    )
}
```

**Contract**:
- `quantity`만큼 `Stock.quantity`를 원자적으로 차감한다.
- 재고 부족 시 예외를 던지고 차감하지 않는다 (`Stock.quantity >= 0` 항상 유지).
- 실패 시 던지는 예외:

| 상황 | 예외 |
|------|------|
| 재고 부족 | `IllegalArgumentException` ("재고 부족") |
| Optimistic 충돌 재시도 초과 | `ObjectOptimisticLockingFailureException` |
| Pessimistic 타임아웃 | `PessimisticLockingFailureException` |
| Distributed 락 획득 실패 | `IllegalStateException` ("분산락 획득 실패") |
| Stock 미존재 | `IllegalArgumentException` ("상품을 찾을 수 없습니다") |

**Implementations**:

| Class | Bean Activation |
|-------|----------------|
| `InventoryOptimisticLockStrategy` | `inventory.lock-strategy: optimistic` |
| `InventoryPessimisticLockStrategy` | `inventory.lock-strategy: pessimistic` |
| `InventoryDistributedLockStrategy` | `inventory.lock-strategy: distributed` |

---

## DistributedLock Annotation

**Location**: `common/lock/DistributedLock.kt`
**Purpose**: Redisson 분산락을 메서드에 선언적으로 적용

```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class DistributedLock(
    val key: String,                                // SpEL expression
    val waitTime: Long = 5000L,
    val leaseTime: Long = 10000L,
    val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
)
```

**Key Expression Examples**:
- 재고 락: `"'lock:stock:' + #productId"`
- 주문 락: `"'lock:order:' + #userId + ':' + #productId"`

**Aspect Ordering**: `@Order(Ordered.LOWEST_PRECEDENCE - 1)` — `@Transactional`보다 먼저 실행
