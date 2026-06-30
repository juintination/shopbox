# Feature Specification: Saga Pattern (Choreography)

**Feature Branch**: `004-saga-choreography`
**Created**: 2026-06-15
**Status**: Draft

## Overview

기존 이커머스 도메인(주문 → 결제 → 재고 → 배송) 흐름에 Choreography Saga Pattern을 적용한다.
각 서비스는 이벤트를 수신하고 성공/실패 이벤트를 발행하며,
실패 시 보상 트랜잭션이 역방향으로 실행된다.
중앙 조율자 없이 각 서비스가 자율적으로 반응하는 구조를 따른다.
보상 트랜잭션도 기존 Outbox Pattern을 그대로 따르며,
보상 이벤트 발행 실패 시 재시도 전략 + DLQ가 동일하게 적용된다.

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

## User Scenarios & Testing

### User Story 1 — 정상 Saga 흐름 (Priority: P1)

주문 생성부터 배송 시작까지 전체 정상 흐름이 이벤트 체인으로 실행된다.
각 서비스는 이전 서비스의 성공 이벤트를 수신하고 다음 서비스로 이벤트를 전파한다.

**Why this priority**: 보상 트랜잭션은 정상 흐름의 각 단계가 먼저 동작해야 의미가 있다.
Kafka Listener + Outbox 연계, 상태 전이, 이벤트 체인이 올바르게 동작하는지 먼저 검증해야 한다.

**Independent Test**: 주문을 생성하고 Kafka 이벤트가 정상적으로 연쇄 발행되는지
Testcontainers(MySQL + Kafka) 환경에서 각 Bounded Context의 상태 변화를 독립 검증할 수 있다.

**Acceptance Scenarios**:

1. **[정상 흐름 - Order]** **Given** 유효한 주문 생성 요청이 있다, **When** `POST /api/orders`를 호출한다, **Then** `orders` 테이블에 `PENDING` 상태의 주문이 저장된다, **Then** `outbox_events`에 `OrderCreated` 이벤트가 저장된다.

2. **[정상 흐름 - Payment]** **Given** `OrderCreated` 이벤트가 발행된다, **When** Payment 서비스가 이벤트를 수신한다, **Then** `payments` 테이블에 `COMPLETED` 상태의 결제가 저장된다, **Then** `outbox_events`에 `PaymentCompleted` 이벤트가 저장된다.

3. **[정상 흐름 - Inventory]** **Given** `PaymentCompleted` 이벤트가 발행된다, **When** Inventory 서비스가 이벤트를 수신한다, **Then** `inventories` 테이블의 재고가 차감되고 `RESERVED` 상태가 된다, **Then** `outbox_events`에 `StockReserved` 이벤트가 저장된다.

4. **[정상 흐름 - Delivery]** **Given** `StockReserved` 이벤트가 발행된다, **When** Delivery 서비스가 이벤트를 수신한다, **Then** `deliveries` 테이블에 `STARTED` 상태의 배송이 저장된다, **Then** `outbox_events`에 `DeliveryStarted` 이벤트가 저장된다.

5. **[Order 최종 상태]** **Given** `DeliveryStarted` 이벤트가 발행된다, **When** Order 서비스가 이벤트를 수신한다, **Then** `orders` 테이블의 주문 상태가 `CONFIRMED`로 업데이트된다.

---

### User Story 2 — 결제 실패 보상 (Priority: P2)

결제 실패 시 역방향으로 주문이 취소된다.

**Why this priority**: 가장 단순한 보상 체인(1단계)으로 보상 트랜잭션 패턴을 먼저 검증한다.
보상 이벤트도 Outbox Pattern을 따르는지, 상태 역전이 올바른지 확인한다.

**Independent Test**: 결제가 실패하도록 강제하고 `PaymentFailed` 이벤트 발행 및
Order 취소까지의 체인을 Testcontainers 환경에서 독립 검증할 수 있다.

**Acceptance Scenarios**:

1. **[결제 실패 - Payment]** **Given** `OrderCreated` 이벤트가 수신된다, **When** 결제 처리 중 실패한다, **Then** `payments` 테이블에 `FAILED` 상태가 저장된다, **Then** `outbox_events`에 `PaymentFailed` 이벤트가 저장된다.

2. **[결제 실패 - Order 취소]** **Given** `PaymentFailed` 이벤트가 발행된다, **When** Order 서비스가 이벤트를 수신한다, **Then** `orders` 테이블의 주문 상태가 `CANCELLED`로 업데이트된다, **Then** `outbox_events`에 `OrderCancelled` 이벤트가 저장된다.

---

### User Story 3 — 재고 부족 보상 (Priority: P3)

재고 부족 시 역방향으로 결제 환불 → 주문 취소가 실행된다.

**Why this priority**: 2단계 보상 체인. US2(결제 실패 보상)가 먼저 검증된 후 진행해야 한다.

**Independent Test**: 재고를 0으로 설정하고 `StockReservationFailed` 이벤트 발행,
Payment 환불, Order 취소까지의 체인을 Testcontainers 환경에서 독립 검증할 수 있다.

**Acceptance Scenarios**:

1. **[재고 부족 - Inventory]** **Given** `PaymentCompleted` 이벤트가 수신된다, **When** 재고가 부족하다, **Then** `inventories` 상태가 `FAILED`로 저장된다, **Then** `outbox_events`에 `StockReservationFailed` 이벤트가 저장된다.

2. **[재고 부족 - Payment 환불]** **Given** `StockReservationFailed` 이벤트가 발행된다, **When** Payment 서비스가 이벤트를 수신한다, **Then** `payments` 테이블의 상태가 `REFUNDED`로 업데이트된다, **Then** `outbox_events`에 `PaymentRefunded` 이벤트가 저장된다.

3. **[재고 부족 - Order 취소]** **Given** `PaymentRefunded` 이벤트가 발행된다, **When** Order 서비스가 이벤트를 수신한다, **Then** `orders` 테이블의 주문 상태가 `CANCELLED`로 업데이트된다, **Then** `outbox_events`에 `OrderCancelled` 이벤트가 저장된다.

---

### User Story 4 — 배송 실패 보상 (Priority: P4)

배송 실패 시 역방향으로 재고 복구 → 결제 환불 → 주문 취소가 실행된다.

**Why this priority**: 3단계 보상 체인. US2, US3이 모두 검증된 후 진행해야 한다.

**Independent Test**: 배송을 실패하도록 강제하고 `DeliveryFailed` 이벤트 발행,
Inventory 복구, Payment 환불, Order 취소까지의 체인을 Testcontainers 환경에서 독립 검증할 수 있다.

**Acceptance Scenarios**:

1. **[배송 실패 - Delivery]** **Given** `StockReserved` 이벤트가 수신된다, **When** 배송 처리 중 실패한다, **Then** `deliveries` 테이블에 `FAILED` 상태가 저장된다, **Then** `outbox_events`에 `DeliveryFailed` 이벤트가 저장된다.

2. **[배송 실패 - Inventory 복구]** **Given** `DeliveryFailed` 이벤트가 발행된다, **When** Inventory 서비스가 이벤트를 수신한다, **Then** `inventories` 재고가 복구되고 상태가 `RESTORED`로 업데이트된다, **Then** `outbox_events`에 `StockRestored` 이벤트가 저장된다.

3. **[배송 실패 - Payment 환불]** **Given** `StockRestored` 이벤트가 발행된다, **When** Payment 서비스가 이벤트를 수신한다, **Then** `payments` 테이블의 상태가 `REFUNDED`로 업데이트된다, **Then** `outbox_events`에 `PaymentRefunded` 이벤트가 저장된다.

4. **[배송 실패 - Order 취소]** **Given** `PaymentRefunded` 이벤트가 발행된다, **When** Order 서비스가 이벤트를 수신한다, **Then** `orders` 테이블의 주문 상태가 `CANCELLED`로 업데이트된다, **Then** `outbox_events`에 `OrderCancelled` 이벤트가 저장된다.

---

### Edge Cases

- 동일한 이벤트를 중복 수신해도 Inbox Pattern 멱등성 처리로 보상 트랜잭션이 두 번 실행되지 않는다.
- 보상 이벤트 발행 자체가 실패하면 Outbox retry + DLQ가 동일하게 동작하여 보상 체인이 멈추지 않는다.
- 이미 `CANCELLED` 상태인 주문에 `PaymentFailed`가 재도달해도 상태가 변경되지 않는다 (멱등성).
- 재고 복구 시 기존 `inventories` 레코드의 수량을 증가시키거나 `RESTORED` 상태로 전이한다.

## Requirements

### Functional Requirements

**Phase 1 — 서비스별 상태 관리 및 Saga 이벤트 처리**

- **FR-001**: `orders` 테이블은 `PENDING → CONFIRMED → CANCELLED` 상태 전이를 지원해야 한다.
- **FR-002**: `payments` 테이블은 `PENDING → COMPLETED → REFUNDED / FAILED` 상태 전이를 지원해야 한다.
- **FR-003**: `inventories` 테이블은 `PENDING → RESERVED → RESTORED / FAILED` 상태 전이를 지원해야 한다.
- **FR-004**: `deliveries` 테이블은 `PENDING → STARTED / FAILED` 상태 전이를 지원해야 한다.
- **FR-005**: 각 Bounded Context는 Kafka Listener로 이벤트를 수신하고 비즈니스 로직 실행 + 보상/후속 이벤트를 같은 트랜잭션에 `outbox_events`에 저장해야 한다.

**Phase 2 — 결제 실패 보상**

- **FR-006**: Payment 서비스는 결제 실패 시 `payments` 상태를 `FAILED`로 변경하고 `PaymentFailed` 이벤트를 `outbox_events`에 저장해야 한다.
- **FR-007**: Order 서비스는 `PaymentFailed` 이벤트를 수신하면 주문 상태를 `CANCELLED`로 변경하고 `OrderCancelled` 이벤트를 `outbox_events`에 저장해야 한다.

**Phase 3 — 재고 부족 보상**

- **FR-008**: Inventory 서비스는 재고 부족 시 `inventories` 상태를 `FAILED`로 변경하고 `StockReservationFailed` 이벤트를 `outbox_events`에 저장해야 한다.
- **FR-009**: Payment 서비스는 `StockReservationFailed` 이벤트를 수신하면 `payments` 상태를 `REFUNDED`로 변경하고 `PaymentRefunded` 이벤트를 `outbox_events`에 저장해야 한다.
- **FR-010**: Order 서비스는 `PaymentRefunded` 이벤트를 수신하면 주문 상태를 `CANCELLED`로 변경하고 `OrderCancelled` 이벤트를 `outbox_events`에 저장해야 한다.

**Phase 4 — 배송 실패 보상**

- **FR-011**: Delivery 서비스는 배송 실패 시 `deliveries` 상태를 `FAILED`로 변경하고 `DeliveryFailed` 이벤트를 `outbox_events`에 저장해야 한다.
- **FR-012**: Inventory 서비스는 `DeliveryFailed` 이벤트를 수신하면 재고를 복구하고 `inventories` 상태를 `RESTORED`로 변경 후 `StockRestored` 이벤트를 `outbox_events`에 저장해야 한다.
- **FR-013**: Payment 서비스는 `StockRestored` 이벤트를 수신하면 `payments` 상태를 `REFUNDED`로 변경하고 `PaymentRefunded` 이벤트를 `outbox_events`에 저장해야 한다.

**공통**

- **FR-014**: 모든 이벤트 처리는 Inbox Pattern으로 멱등성을 보장해야 한다 (동일 `eventId` 중복 처리 금지).
- **FR-015**: 보상 이벤트 스키마는 기존 공통 필드(`eventId`, `eventType`, `occurredAt`, `payload`) 외에 `reason` 필드(실패 원인)를 포함해야 한다.

### Service State Transitions

```
Order:     PENDING → CONFIRMED (DeliveryStarted 수신)
                   → CANCELLED (PaymentFailed / PaymentRefunded 수신)

Payment:   PENDING → COMPLETED (OrderCreated 수신, 결제 성공)
                   → FAILED    (OrderCreated 수신, 결제 실패)
                   → REFUNDED  (StockReservationFailed / StockRestored 수신)

Inventory: PENDING → RESERVED  (PaymentCompleted 수신, 재고 충분)
                   → FAILED    (PaymentCompleted 수신, 재고 부족)
                   → RESTORED  (DeliveryFailed 수신)

Delivery:  PENDING → STARTED   (StockReserved 수신, 배송 성공)
                   → FAILED    (StockReserved 수신, 배송 실패)
```

### Event Table

#### 정상 이벤트

| 이벤트                | 발행 서비스    | 구독 서비스    | Topic              |
|--------------------|-----------|-----------|--------------------|
| `OrderCreated`     | Order     | Payment   | `order-events`     |
| `PaymentCompleted` | Payment   | Inventory | `payment-events`   |
| `StockReserved`    | Inventory | Delivery  | `inventory-events` |
| `DeliveryStarted`  | Delivery  | Order     | `delivery-events`  |

#### 보상 이벤트

| 이벤트                      | 발행 서비스    | 구독 서비스    | Topic              |
|--------------------------|-----------|-----------|--------------------|
| `PaymentFailed`          | Payment   | Order     | `payment-events`   |
| `StockReservationFailed` | Inventory | Payment   | `inventory-events` |
| `DeliveryFailed`         | Delivery  | Inventory | `delivery-events`  |
| `PaymentRefunded`        | Payment   | Order     | `payment-events`   |
| `StockRestored`          | Inventory | Payment   | `inventory-events` |
| `OrderCancelled`         | Order     | -         | `order-events`     |

### Event Schema

모든 이벤트는 다음 공통 스키마를 따른다.
보상 이벤트는 추가로 `reason` 필드를 포함한다.

```json
{
  "eventId": "string (TSID)",
  "eventType": "string",
  "occurredAt": "string (ISO 8601)",
  "payload": { ... },
  "reason": "string (보상 이벤트에만 포함, 실패 원인)"
}
```

### 전체 흐름

```
POST /api/orders
      │
      ▼
Order 생성 (PENDING) + outbox: OrderCreated
      │
      ▼ [Kafka: order-events]
Payment 처리
  ├── 성공 → Payment(COMPLETED) + outbox: PaymentCompleted
  └── 실패 → Payment(FAILED)   + outbox: PaymentFailed
                    │
                    ▼ [Kafka: payment-events]
              Order(CANCELLED) + outbox: OrderCancelled
      │
      ▼ [Kafka: payment-events]
Inventory 차감
  ├── 성공 → Inventory(RESERVED) + outbox: StockReserved
  └── 실패 → Inventory(FAILED)  + outbox: StockReservationFailed
                    │
                    ▼ [Kafka: inventory-events]
              Payment(REFUNDED) + outbox: PaymentRefunded
                    │
                    ▼ [Kafka: payment-events]
              Order(CANCELLED)  + outbox: OrderCancelled
      │
      ▼ [Kafka: inventory-events]
Delivery 처리
  ├── 성공 → Delivery(STARTED) + outbox: DeliveryStarted
  │               │
  │               ▼ [Kafka: delivery-events]
  │         Order(CONFIRMED)
  └── 실패 → Delivery(FAILED) + outbox: DeliveryFailed
                    │
                    ▼ [Kafka: delivery-events]
              Inventory(RESTORED) + outbox: StockRestored
                    │
                    ▼ [Kafka: inventory-events]
              Payment(REFUNDED)   + outbox: PaymentRefunded
                    │
                    ▼ [Kafka: payment-events]
              Order(CANCELLED)    + outbox: OrderCancelled
```

## Success Criteria

- **SC-001**: 주문 생성 후 Kafka 이벤트 체인이 정상 동작하면 Order 상태가 `CONFIRMED`로 전이된다.
- **SC-002**: 결제 실패 시 Order 상태가 `CANCELLED`로 전이되고 `OrderCancelled` 이벤트가 발행된다.
- **SC-003**: 재고 부족 시 Payment 상태가 `REFUNDED`, Order 상태가 `CANCELLED`로 전이된다.
- **SC-004**: 배송 실패 시 Inventory 상태가 `RESTORED`, Payment 상태가 `REFUNDED`, Order 상태가 `CANCELLED`로 전이된다.
- **SC-005**: 동일 이벤트를 중복 수신해도 보상 트랜잭션이 두 번 실행되지 않는다 (Inbox 멱등성).
- **SC-006**: 보상 이벤트 발행 실패 시 기존 retry + DLQ 전략이 동일하게 적용된다.

## Assumptions

- 실패 시나리오 제어는 서비스 내부 로직(예: 재고 수량 0, 결제 처리 플래그)으로 구현한다.
- Kafka Listener는 기존 `@KafkaListener` + Inbox Pattern 구조를 그대로 재사용한다.
- 각 Bounded Context의 `payments`, `inventories`, `deliveries` 테이블은 이미 존재하며 상태 컬럼만 추가/변경한다.
- 인증/인가는 이 스펙의 범위 밖이다.
- 자동 재처리 스케줄러(보상 체인 타임아웃) 및 Saga 상태 추적 테이블은 이 스펙의 범위 밖이다.
