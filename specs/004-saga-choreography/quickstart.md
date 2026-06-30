# Quickstart: Saga Pattern (Choreography)

**Branch**: `004-saga-choreography` | **Date**: 2026-06-15

---

## 전제 조건

- MySQL + Kafka가 Testcontainers로 실행 중
- Stock 테이블에 `productId`에 해당하는 재고가 충분히 존재 (정상 흐름용)
- Message Relay가 `outbox_events`를 폴링하여 Kafka에 발행 중

---

## 시나리오 1: 정상 Saga 흐름

**목표**: Order → Payment → Inventory → Delivery → Order(CONFIRMED) 체인 검증

```
1. POST /api/orders
   → orders(PENDING) 생성 + outbox: OrderCreated

2. MessageRelay 실행 → Kafka: order-events ← OrderCreated

3. PaymentService.onOrderCreated()
   → payments(COMPLETED) 저장 + outbox: PaymentCompleted

4. MessageRelay 실행 → Kafka: payment-events ← PaymentCompleted

5. InventoryService.onPaymentCompleted()
   → inventories(RESERVED) 저장, Stock 차감 + outbox: StockReserved

6. MessageRelay 실행 → Kafka: inventory-events ← StockReserved

7. DeliveryService.onStockReserved()
   → deliveries(STARTED) 저장 + outbox: DeliveryStarted

8. MessageRelay 실행 → Kafka: delivery-events ← DeliveryStarted

9. OrderService.onDeliveryStarted()
   → orders 상태 → CONFIRMED
```

**검증 포인트**:
- `orders.status = CONFIRMED`
- `payments.status = COMPLETED`
- `inventories.status = RESERVED`
- `deliveries.status = STARTED`
- `outbox_events`에 4개 이벤트 (`processed_at IS NOT NULL`)

---

## 시나리오 2: 결제 실패 보상

**목표**: PaymentFailed → OrderCancelled 체인 검증

```
1. [시나리오 1의 1단계 완료 상태]
   → orders(PENDING) 존재, outbox: OrderCreated 발행됨

2. PaymentService.processPaymentFailed(messageId, payload) 직접 호출
   - payload: { eventType: "PaymentFailed", orderId: ..., reason: "결제 실패" }
   → payments(FAILED) 저장 + outbox: PaymentFailed

3. MessageRelay 실행 → Kafka: payment-events ← PaymentFailed

4. OrderService.onPaymentEvent()
   → eventType = "PaymentFailed" 분기
   → orders 상태 → CANCELLED + outbox: OrderCancelled
```

**검증 포인트**:
- `orders.status = CANCELLED`
- `payments.status = FAILED`
- `outbox_events`에 `PaymentFailed`, `OrderCancelled` 이벤트 존재

---

## 시나리오 3: 재고 부족 보상

**목표**: StockReservationFailed → PaymentRefunded → OrderCancelled 체인 검증

```
1. [시나리오 1의 3단계까지 완료]
   → orders(PENDING), payments(COMPLETED) 존재

2. InventoryService.processStockReservationFailed(messageId, payload) 직접 호출
   - payload: { eventType: "StockReservationFailed", orderId: ..., reason: "재고 부족" }
   → inventories(FAILED) 저장 + outbox: StockReservationFailed

3. MessageRelay 실행 → Kafka: inventory-events ← StockReservationFailed

4. PaymentService.onInventoryEvent()
   → eventType = "StockReservationFailed" 분기
   → payments 상태 → REFUNDED + outbox: PaymentRefunded

5. MessageRelay 실행 → Kafka: payment-events ← PaymentRefunded

6. OrderService.onPaymentEvent()
   → eventType = "PaymentRefunded" 분기
   → orders 상태 → CANCELLED + outbox: OrderCancelled
```

**검증 포인트**:
- `orders.status = CANCELLED`
- `payments.status = REFUNDED`
- `inventories.status = FAILED`
- `outbox_events`에 `StockReservationFailed`, `PaymentRefunded`, `OrderCancelled` 존재

---

## 시나리오 4: 배송 실패 보상

**목표**: DeliveryFailed → StockRestored → PaymentRefunded → OrderCancelled 체인 검증

```
1. [시나리오 1의 7단계까지 완료]
   → orders(PENDING), payments(COMPLETED), inventories(RESERVED) 존재

2. DeliveryService.processDeliveryFailed(messageId, payload) 직접 호출
   - payload: { eventType: "DeliveryFailed", orderId: ..., reason: "배송 실패" }
   → deliveries(FAILED) 저장 + outbox: DeliveryFailed

3. MessageRelay 실행 → Kafka: delivery-events ← DeliveryFailed

4. InventoryService.onDeliveryFailed()
   → Stock 복구 + inventories 상태 → RESTORED + outbox: StockRestored

5. MessageRelay 실행 → Kafka: inventory-events ← StockRestored

6. PaymentService.onInventoryEvent()
   → eventType = "StockRestored" 분기
   → payments 상태 → REFUNDED + outbox: PaymentRefunded

7. MessageRelay 실행 → Kafka: payment-events ← PaymentRefunded

8. OrderService.onPaymentEvent()
   → eventType = "PaymentRefunded" 분기
   → orders 상태 → CANCELLED + outbox: OrderCancelled
```

**검증 포인트**:
- `orders.status = CANCELLED`
- `payments.status = REFUNDED`
- `inventories.status = RESTORED`
- `deliveries.status = FAILED`
- `outbox_events`에 `DeliveryFailed`, `StockRestored`, `PaymentRefunded`, `OrderCancelled` 존재

---

## 시나리오 5: 멱등성 — 동일 이벤트 중복 수신

**목표**: Inbox Pattern이 보상 이벤트에서도 동작함을 검증

```
1. PaymentService.processPaymentFailed("msg-id-001", payload) 호출
   → payments(FAILED) 저장, outbox: PaymentFailed 저장

2. 동일 messageId로 재호출
   PaymentService.processPaymentFailed("msg-id-001", payload) 재호출
   → "Duplicate message skipped" 로그 → 아무 변경 없음
```

**검증 포인트**:
- `outbox_events`에 `PaymentFailed` 이벤트가 1건만 존재
- `payments` 테이블에 중복 레코드 없음

---

## 통합 테스트 파일 매핑

| 시나리오          | 테스트 파일                            | 테스트 방식                           |
|---------------|-------------------------------------|-------------------------------------|
| 시나리오 1        | `order/controller/OrderControllerTest.kt` | `@SpringBootTest` + Testcontainers  |
| 시나리오 2, 3, 4, 5 | `order/service/OrderServiceTest.kt`   | Mockk 단위 테스트 (Listener 직접 호출)  |
|               | `payment/service/PaymentServiceTest.kt` | Mockk 단위 테스트 (Listener 직접 호출) |
|               | `inventory/service/InventoryServiceTest.kt` | Mockk 단위 테스트                 |
|               | `delivery/service/DeliveryServiceTest.kt` | Mockk 단위 테스트                   |
