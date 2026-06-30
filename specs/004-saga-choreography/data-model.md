# Data Model: Saga Pattern (Choreography)

**Branch**: `004-saga-choreography` | **Date**: 2026-06-15

---

## 기존 Entity 변경

### OrderStatus (변경)

```kotlin
// 기존
enum class OrderStatus {
    PENDING,
    PAID,
    CANCELLED,
}

// 변경 후
enum class OrderStatus {
    PENDING,
    PAID,        // 기존 유지 (레거시)
    CONFIRMED,   // 추가 — DeliveryStarted 수신 후 Saga 전체 성공
    CANCELLED,
}
```

**전이 규칙**:
```
PENDING → CONFIRMED   (DeliveryStarted 수신)
PENDING → CANCELLED   (PaymentFailed / PaymentRefunded 수신)
```

---

### PaymentStatus (변경)

```kotlin
// 기존
enum class PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
}

// 변경 후
enum class PaymentStatus {
    PENDING,
    COMPLETED,
    REFUNDED,    // 추가 — 보상 트랜잭션으로 환불됨
    FAILED,
}
```

**전이 규칙**:
```
PENDING → COMPLETED  (OrderCreated 수신, 결제 성공)
PENDING → FAILED     (OrderCreated 수신, 결제 실패)
COMPLETED → REFUNDED (StockReservationFailed / StockRestored 수신)
```

---

### InventoryStatus (변경)

```kotlin
// 기존
enum class InventoryStatus {
    PENDING,
    RESERVED,
    FAILED,
}

// 변경 후
enum class InventoryStatus {
    PENDING,
    RESERVED,
    RESTORED,    // 추가 — DeliveryFailed 수신 후 재고 복구됨
    FAILED,
}
```

**전이 규칙**:
```
PENDING  → RESERVED  (PaymentCompleted 수신, 재고 충분)
PENDING  → FAILED    (PaymentCompleted 수신, 재고 부족)
RESERVED → RESTORED  (DeliveryFailed 수신)
```

---

### Payment (기존 메서드 수정)

`processOrderCreated` 내에서 `payment.status = PaymentStatus.COMPLETED` 업데이트 추가.  
현재 코드는 Payment를 `PENDING` 상태로 저장하고 `PaymentCompleted` 이벤트를 발행하는 불일치 존재.

---

### Inventory (기존 메서드 수정)

`processPaymentCompleted` 내에서 `inventory.status = InventoryStatus.RESERVED` 업데이트 추가.

---

### Delivery (기존 메서드 수정)

`processStockReserved` 내에서 `delivery.status = DeliveryStatus.STARTED` 업데이트 추가.

---

## 신규 Interface

### CompensationEvent

```kotlin
// common/event/CompensationEvent.kt
package com.example.shopbox.common.event

interface CompensationEvent : DomainEvent {
    val reason: String
}
```

---

## 신규 Event Classes

### OrderCancelledEvent

```kotlin
// order/event/OrderCancelledEvent.kt
data class OrderCancelledEvent(
    override val eventId: String = TSID.fast().toString(),
    override val eventType: String = EVENT_TYPE,
    override val occurredAt: String = Instant.now().toString(),
    override val reason: String,
    val orderId: Long,
) : CompensationEvent {
    companion object {
        const val EVENT_TYPE = "OrderCancelled"
        const val AGGREGATE_TYPE = "Order"
        const val TOPIC = "order-events"
    }
}
```

---

### PaymentFailedEvent

```kotlin
// payment/event/PaymentFailedEvent.kt
data class PaymentFailedEvent(
    override val eventId: String = TSID.fast().toString(),
    override val eventType: String = EVENT_TYPE,
    override val occurredAt: String = Instant.now().toString(),
    override val reason: String,
    val paymentId: Long,
    val orderId: Long,
) : CompensationEvent {
    companion object {
        const val EVENT_TYPE = "PaymentFailed"
        const val AGGREGATE_TYPE = "Payment"
        const val TOPIC = "payment-events"
    }
}
```

---

### PaymentRefundedEvent

```kotlin
// payment/event/PaymentRefundedEvent.kt
data class PaymentRefundedEvent(
    override val eventId: String = TSID.fast().toString(),
    override val eventType: String = EVENT_TYPE,
    override val occurredAt: String = Instant.now().toString(),
    override val reason: String,
    val paymentId: Long,
    val orderId: Long,
) : CompensationEvent {
    companion object {
        const val EVENT_TYPE = "PaymentRefunded"
        const val AGGREGATE_TYPE = "Payment"
        const val TOPIC = "payment-events"
    }
}
```

---

### StockReservationFailedEvent

```kotlin
// inventory/event/StockReservationFailedEvent.kt
data class StockReservationFailedEvent(
    override val eventId: String = TSID.fast().toString(),
    override val eventType: String = EVENT_TYPE,
    override val occurredAt: String = Instant.now().toString(),
    override val reason: String,
    val orderId: Long,
    val productId: Long,
    val quantity: Int,
) : CompensationEvent {
    companion object {
        const val EVENT_TYPE = "StockReservationFailed"
        const val AGGREGATE_TYPE = "Inventory"
        const val TOPIC = "inventory-events"
    }
}
```

---

### StockRestoredEvent

```kotlin
// inventory/event/StockRestoredEvent.kt
data class StockRestoredEvent(
    override val eventId: String = TSID.fast().toString(),
    override val eventType: String = EVENT_TYPE,
    override val occurredAt: String = Instant.now().toString(),
    override val reason: String,
    val inventoryId: Long,
    val orderId: Long,
    val productId: Long,
    val quantity: Int,
) : CompensationEvent {
    companion object {
        const val EVENT_TYPE = "StockRestored"
        const val AGGREGATE_TYPE = "Inventory"
        const val TOPIC = "inventory-events"
    }
}
```

---

### DeliveryFailedEvent

```kotlin
// delivery/event/DeliveryFailedEvent.kt
data class DeliveryFailedEvent(
    override val eventId: String = TSID.fast().toString(),
    override val eventType: String = EVENT_TYPE,
    override val occurredAt: String = Instant.now().toString(),
    override val reason: String,
    val deliveryId: Long,
    val orderId: Long,
) : CompensationEvent {
    companion object {
        const val EVENT_TYPE = "DeliveryFailed"
        const val AGGREGATE_TYPE = "Delivery"
        const val TOPIC = "delivery-events"
    }
}
```

---

## 신규/변경 Kafka Listeners 요약

| 서비스              | 구독 토픽           | 처리 이벤트 타입                            | 메서드                        |
|-----------------|-----------------|---------------------------------------|---------------------------|
| `OrderService`  | `payment-events`  | `PaymentFailed` / `PaymentRefunded`   | `onPaymentEvent()`         |
| `OrderService`  | `delivery-events` | `DeliveryStarted`                     | `onDeliveryStarted()` (신규) |
| `PaymentService`| `inventory-events`| `StockReservationFailed` / `StockRestored` | `onInventoryEvent()` (신규) |
| `InventoryService` | `delivery-events` | `DeliveryFailed`                  | `onDeliveryFailed()` (신규) |

> 기존 `PaymentService.onOrderCreated()`, `InventoryService.onPaymentCompleted()`, `DeliveryService.onStockReserved()`는 유지.

---

## 변경 파일 목록

### 기존 변경

| 파일                                                | 변경 내용                              |
|----------------------------------------------------|--------------------------------------|
| `order/entity/enums/OrderStatus.kt`                | `CONFIRMED` 추가                      |
| `payment/entity/enums/PaymentStatus.kt`            | `REFUNDED` 추가                       |
| `inventory/entity/enums/InventoryStatus.kt`        | `RESTORED` 추가                       |
| `payment/service/PaymentService.kt`                | status 업데이트 + 실패 처리 + 보상 Listener |
| `inventory/service/InventoryService.kt`            | status 업데이트 + 실패 처리 + 보상 Listener |
| `delivery/service/DeliveryService.kt`              | status 업데이트 + 실패 처리               |
| `order/service/OrderService.kt`                    | Saga Listener 추가                    |

### 신규 파일

| 파일                                                    | 설명               |
|-------------------------------------------------------|------------------|
| `common/event/CompensationEvent.kt`                   | 보상 이벤트 인터페이스     |
| `order/event/OrderCancelledEvent.kt`                  | 주문 취소 이벤트        |
| `payment/event/PaymentFailedEvent.kt`                 | 결제 실패 이벤트        |
| `payment/event/PaymentRefundedEvent.kt`               | 결제 환불 이벤트        |
| `inventory/event/StockReservationFailedEvent.kt`      | 재고 예약 실패 이벤트     |
| `inventory/event/StockRestoredEvent.kt`               | 재고 복구 이벤트        |
| `delivery/event/DeliveryFailedEvent.kt`               | 배송 실패 이벤트        |
| `order/service/OrderServiceTest.kt`                   | Order 보상 단위 테스트  |
| `payment/service/PaymentServiceTest.kt`               | Payment 보상 단위 테스트|
| `inventory/service/InventoryServiceTest.kt`           | Inventory 보상 단위 테스트|
| `delivery/service/DeliveryServiceTest.kt`             | Delivery 보상 단위 테스트|
| `order/controller/OrderControllerTest.kt` (기존 수정)  | Saga 통합 테스트 추가   |
