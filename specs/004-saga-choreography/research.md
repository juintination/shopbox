# Research: Saga Pattern (Choreography)

**Branch**: `004-saga-choreography` | **Date**: 2026-06-15

---

## Decision 1: OrderStatus — CONFIRMED 추가

**Decision**: 기존 `PAID`를 유지하고 `CONFIRMED`를 추가한다.

**Rationale**: `PAID`는 기존 코드에서 어디에서도 명시적으로 할당되지 않는다 (Order 생성 시 항상 `PENDING`으로 시작). 반면 `CONFIRMED`는 Saga에서 명확한 의미를 가진다 — `DeliveryStarted` 이벤트 수신 후 전체 흐름이 성공한 최종 상태. 리네이밍 대신 추가를 선택하여 기존 테스트 코드 파손 위험을 제거한다.

**Alternatives considered**:
- `PAID → CONFIRMED` 리네이밍 — 기존 `OrderControllerTest` 등에서 `PAID`를 참조할 경우 컴파일 오류 위험

---

## Decision 2: PaymentStatus — REFUNDED 추가

**Decision**: `REFUNDED` 상태를 추가한다.

**Rationale**: `FAILED`는 결제 처리 자체가 실패한 상태이고, `REFUNDED`는 결제가 성공한 후 Saga 보상 트랜잭션으로 환불된 상태다. 두 상태를 구분해야 운영 시 실패 원인 추적이 가능하다.

**Alternatives considered**:
- `FAILED` 단일 상태로 통합 — 실패 유형(처리 실패 vs. 보상 환불) 구분 불가

---

## Decision 3: InventoryStatus — RESTORED 추가

**Decision**: `RESTORED` 상태를 추가한다.

**Rationale**: `FAILED`는 재고 예약 자체가 실패한 것이고, `RESTORED`는 예약 성공 후 배송 실패로 재고가 복구된 것이다. 구분하지 않으면 재고 이력 추적이 불가능하다.

**Alternatives considered**:
- `FAILED` 단일 상태로 통합 — `RESERVED → (복구 후) FAILED`는 의미상 부정확

---

## Decision 4: CompensationEvent 인터페이스 분리

**Decision**: `common/event/CompensationEvent.kt`를 신규 생성하고 `reason: String` 필드를 추가한다. 보상 이벤트 클래스는 `DomainEvent` 대신 `CompensationEvent`를 구현한다.

**Rationale**: `DomainEvent`에 `reason: String? = null`을 추가하면 정상 이벤트(`OrderCreated`, `PaymentCompleted` 등) 모두에 의미 없는 null 필드가 생긴다. 분리하면 타입 안전성이 높아지고 보상 이벤트 처리 시 `reason`이 강제된다.

**Interface**:
```kotlin
interface CompensationEvent : DomainEvent {
    val reason: String
}
```

**Alternatives considered**:
- `DomainEvent`에 `reason: String? = null` 추가 — 정상 이벤트에 불필요한 nullable 필드 노출

---

## Decision 5: 실패 시뮬레이션 전략

**Decision**: 보상 Listener 핸들러(`processPaymentFailed`, `processDeliveryFailed` 등)를 public 메서드로 노출하고 테스트에서 직접 호출한다.

**Rationale**: 프로덕션 코드에 테스트 전용 플래그(`shouldFail`, `test.payment.always-fail` 등)를 주입하지 않는다. 각 서비스의 보상 핸들러는 public 트랜잭션 메서드이므로 `ControllerTest`에서 직접 호출하여 독립 검증할 수 있다. 정상 흐름 테스트는 기존 `OrderControllerTest`에서 이미 검증된 구조를 재사용한다.

**Alternatives considered**:
- `shouldFail: Boolean` 파라미터 — 프로덕션 코드 오염
- `application-test.yml` 실패 플래그 — 테스트 간 공유 상태 위험, 통합 테스트에서 병렬 실행 불가

---

## Decision 6: Entity status 업데이트 누락 수정

**Decision**: 기존 `processOrderCreated`, `processPaymentCompleted`, `processStockReserved` 메서드에 저장 후 status 업데이트를 추가한다.

**Rationale**: 현재 코드에서 Payment/Inventory/Delivery는 생성 시 `PENDING` 상태이며 저장 후에도 그대로다. Saga 상태 추적을 위해 성공 시 각 status를 `COMPLETED/RESERVED/STARTED`로 업데이트해야 한다.

구체적 수정:
- `PaymentService.processOrderCreated()`: `payment.status = PaymentStatus.COMPLETED` 추가
- `InventoryService.processPaymentCompleted()`: `inventory.status = InventoryStatus.RESERVED` 추가
- `DeliveryService.processStockReserved()`: `delivery.status = DeliveryStatus.STARTED` 추가

**Alternatives considered**:
- 초기 create 시 COMPLETED로 생성 — 이벤트 발행 전 성공을 가정하는 잘못된 선처리

---

## Decision 7: 동일 토픽의 여러 이벤트 타입 처리

**Decision**: `OrderService`에서 `payment-events` 토픽을 구독할 때 하나의 `@KafkaListener`로 수신하고 `eventType` 필드로 분기 처리한다.

**Rationale**: `payment-events` 토픽에는 `PaymentFailed`와 `PaymentRefunded`가 모두 발행된다. 두 이벤트를 별도 `@KafkaListener`로 분리하면 groupId가 동일한 두 consumer가 같은 토픽을 구독하게 되어 메시지가 두 리스너 중 하나에만 도달하는 문제가 생긴다. 하나의 `@KafkaListener`에서 `eventType`으로 분기하는 것이 안전하다.

동일한 패턴이 적용되는 케이스:
- `OrderService`: `payment-events` 구독 → `PaymentFailed` / `PaymentRefunded` 분기
- `PaymentService`: `inventory-events` 구독 → `StockReservationFailed` / `StockRestored` 분기

**Alternatives considered**:
- 이벤트 타입별 전용 토픽 (`payment-failed-events`, `payment-refunded-events`) — 토픽 수 폭발, 운영 복잡도 증가

---

## Decision 8: 보상 Listener 추가 위치

**Decision**: 보상 로직을 기존 서비스(`OrderService`, `PaymentService`, `InventoryService`)에 Kafka Listener 메서드로 추가한다.

**Rationale**: 보상 트랜잭션도 동일 Bounded Context의 도메인 로직이다. `OrderService`가 Order 상태를 관리하는 책임을 가지므로 `PaymentFailed`/`PaymentRefunded` 수신 후 Order 취소 로직도 `OrderService`에 위치하는 것이 응집도상 적절하다.

**Alternatives considered**:
- 별도 `SagaCompensationService` — 단일 책임 원칙 위반, Bounded Context 경계 무너짐
- 별도 `OrderSagaListener`, `PaymentSagaListener` 컴포넌트 — 서비스와 리스너 역할 분리는 명확하지만 이 프로젝트 규모에서 과도한 분리
