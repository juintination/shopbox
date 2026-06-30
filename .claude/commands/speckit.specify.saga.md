# Specify - Saga Pattern (Choreography)

다음 요구사항을 기반으로 프로젝트 스펙을 작성해줘.

## 프로젝트 개요

기존 이커머스 도메인(주문 → 결제 → 재고 → 배송) 흐름에
Choreography Saga Pattern을 적용한다.
각 서비스는 이벤트를 수신하고 성공/실패 이벤트를 발행하며,
실패 시 보상 트랜잭션이 역방향으로 실행된다.
중앙 조율자 없이 각 서비스가 자율적으로 반응하는 구조를 따른다.

## 정상 흐름

```
Order 생성
  → OrderCreated 발행
    → Payment 처리
      → PaymentCompleted 발행
        → Inventory 차감
          → StockReserved 발행
            → Delivery 시작
              → DeliveryStarted 발행 (완료)
```

## 보상 트랜잭션 흐름

### 결제 실패

```
PaymentFailed 발행
  → 주문 취소 (OrderCancelled)
```

### 재고 부족

```
StockReservationFailed 발행
  → 결제 환불 (PaymentRefunded)
    → 주문 취소 (OrderCancelled)
```

### 배송 실패

```
DeliveryFailed 발행
  → 재고 복구 (StockRestored)
    → 결제 환불 (PaymentRefunded)
      → 주문 취소 (OrderCancelled)
```

## 이벤트 목록

### 정상 이벤트

| 이벤트                | 발행 서비스    | 구독 서비스    | Topic              |
|--------------------|-----------|-----------|--------------------|
| `OrderCreated`     | Order     | Payment   | `order-events`     |
| `PaymentCompleted` | Payment   | Inventory | `payment-events`   |
| `StockReserved`    | Inventory | Delivery  | `inventory-events` |
| `DeliveryStarted`  | Delivery  | -         | `delivery-events`  |

### 보상 이벤트

| 이벤트                      | 발행 서비스    | 구독 서비스    | Topic              |
|--------------------------|-----------|-----------|--------------------|
| `PaymentFailed`          | Payment   | Order     | `payment-events`   |
| `StockReservationFailed` | Inventory | Payment   | `inventory-events` |
| `DeliveryFailed`         | Delivery  | Inventory | `delivery-events`  |
| `PaymentRefunded`        | Payment   | Order     | `payment-events`   |
| `StockRestored`          | Inventory | Payment   | `inventory-events` |
| `OrderCancelled`         | Order     | -         | `order-events`     |

## 서비스별 상태 관리

각 서비스는 Saga 진행 상태를 추적하기 위해 상태값을 관리한다:

### Order 상태

```
PENDING → CONFIRMED → CANCELLED
```

### Payment 상태

```
PENDING → COMPLETED → REFUNDED → FAILED
```

### Inventory 상태

```
PENDING → RESERVED → RESTORED → FAILED
```

### Delivery 상태

```
PENDING → STARTED → FAILED
```

## 시나리오

### [정상 흐름]

**[정상 - 전체 흐름 성공]**

- Given: 주문, 결제, 재고, 배송 모두 정상 동작한다
- When: 사용자가 주문을 생성한다
- Then: `OrderCreated` → `PaymentCompleted` → `StockReserved` → `DeliveryStarted` 순서로 이벤트가 발행된다
- Then: Order 상태가 `CONFIRMED`가 된다

### [보상 트랜잭션 - 결제 실패]

**[결제 실패 → 주문 취소]**

- Given: 결제 처리 중 실패가 발생한다
- When: Payment 서비스가 `OrderCreated` 이벤트를 수신한다
- Then: `PaymentFailed` 이벤트가 발행된다
- Then: Order 서비스가 `PaymentFailed`를 수신하여 주문을 취소한다
- Then: `OrderCancelled` 이벤트가 발행된다
- Then: Order 상태가 `CANCELLED`가 된다

### [보상 트랜잭션 - 재고 부족]

**[재고 부족 → 결제 환불 → 주문 취소]**

- Given: 재고가 부족하다
- When: Inventory 서비스가 `PaymentCompleted` 이벤트를 수신한다
- Then: `StockReservationFailed` 이벤트가 발행된다
- Then: Payment 서비스가 `StockReservationFailed`를 수신하여 결제를 환불한다
- Then: `PaymentRefunded` 이벤트가 발행된다
- Then: Order 서비스가 `PaymentRefunded`를 수신하여 주문을 취소한다
- Then: `OrderCancelled` 이벤트가 발행된다
- Then: Order 상태가 `CANCELLED`, Payment 상태가 `REFUNDED`가 된다

### [보상 트랜잭션 - 배송 실패]

**[배송 실패 → 재고 복구 → 결제 환불 → 주문 취소]**

- Given: 배송 처리 중 실패가 발생한다
- When: Delivery 서비스가 `StockReserved` 이벤트를 수신한다
- Then: `DeliveryFailed` 이벤트가 발행된다
- Then: Inventory 서비스가 `DeliveryFailed`를 수신하여 재고를 복구한다
- Then: `StockRestored` 이벤트가 발행된다
- Then: Payment 서비스가 `StockRestored`를 수신하여 결제를 환불한다
- Then: `PaymentRefunded` 이벤트가 발행된다
- Then: Order 서비스가 `PaymentRefunded`를 수신하여 주문을 취소한다
- Then: `OrderCancelled` 이벤트가 발행된다
- Then: Order 상태가 `CANCELLED`, Payment 상태가 `REFUNDED`, Inventory 상태가 `RESTORED`가 된다

## 보상 이벤트와 Outbox Pattern

보상 트랜잭션도 기존 Outbox Pattern을 그대로 따른다:

- 보상 이벤트도 비즈니스 데이터 변경과 같은 트랜잭션에 `outbox_events`에 저장한다
- Message Relay가 `outbox_events`를 폴링하여 Kafka에 발행한다
- 보상 이벤트 발행 실패 시 재시도 전략 + DLQ가 동일하게 적용된다

## 이벤트 스키마 추가

보상 이벤트는 기존 공통 필드 외에 실패 원인을 포함한다:

| 필드           | 타입     | 설명                  |
|--------------|--------|---------------------|
| `eventId`    | String | 고유 식별자              |
| `eventType`  | String | 이벤트 종류              |
| `occurredAt` | String | 발생 시각 (ISO 8601)    |
| `payload`    | Object | 이벤트별 데이터            |
| `reason`     | String | 실패 원인 (보상 이벤트에만 포함) |
