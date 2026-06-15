# Tasks: Saga Pattern (Choreography)

**Input**: Design documents from `/specs/004-saga-choreography/`
**Prerequisites**: plan.md ✓, spec.md ✓, research.md ✓, data-model.md ✓, contracts/ ✓, quickstart.md ✓

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일, 선행 의존성 없음 → 병렬 실행 가능
- **[Story]**: 속한 User Story (US1~US4)
- TDD 필수 — 테스트 먼저 Red 확인 후 구현

---

## Phase 1: Foundational (모든 User Story의 전제 조건)

**Purpose**: 보상 이벤트 인터페이스 + 상태 enum 확장 — US1~US4 전부 이 Phase 완료 후 시작

**⚠️ CRITICAL**: 이 Phase 완료 전에는 어떤 User Story도 시작할 수 없음

- [ ] T001 `common/event/CompensationEvent.kt` 신규 생성 — `DomainEvent`를 상속하는 `reason: String` 필드 추가 인터페이스 (`interface CompensationEvent : DomainEvent { val reason: String }`)
- [ ] T002 [P] `order/entity/enums/OrderStatus.kt` 변경 — `CONFIRMED` 추가 (`PENDING, PAID, CONFIRMED, CANCELLED`)
- [ ] T003 [P] `payment/entity/enums/PaymentStatus.kt` 변경 — `REFUNDED` 추가 (`PENDING, COMPLETED, REFUNDED, FAILED`)
- [ ] T004 [P] `inventory/entity/enums/InventoryStatus.kt` 변경 — `RESTORED` 추가 (`PENDING, RESERVED, RESTORED, FAILED`)

**Checkpoint**: enum 변경 후 `./gradlew build` 통과 확인. T002~T004는 병렬 실행 가능 (서로 다른 파일).

---

## Phase 2: User Story 1 — 정상 Saga 흐름 (Priority: P1) 🎯 MVP

**Goal**: Payment.status=COMPLETED, Inventory.status=RESERVED, Delivery.status=STARTED 업데이트 + OrderService가 DeliveryStarted 수신 후 Order.status=CONFIRMED 전이

**Independent Test**: `PaymentServiceTest`, `InventoryServiceTest`, `DeliveryServiceTest`, `OrderServiceTest`에서 각 상태 업데이트를 Mockk으로 독립 검증. `OrderControllerTest`에서 정상 흐름 통합 검증.

### Tests for User Story 1 (🔴 Red 먼저 확인)

- [ ] T005 [P] [US1] `src/test/kotlin/com/example/shopbox/payment/service/PaymentServiceTest.kt` 신규 작성 — BehaviorSpec, Mockk, 시나리오: `processOrderCreated()` 호출 시 `payment.status = COMPLETED`로 저장되고 `PaymentCompleted` outbox 이벤트가 저장됨
- [ ] T006 [P] [US1] `src/test/kotlin/com/example/shopbox/inventory/service/InventoryServiceTest.kt` 신규 작성 — BehaviorSpec, Mockk, 시나리오: `processPaymentCompleted()` 호출 시 `inventory.status = RESERVED`로 저장되고 `StockReserved` outbox 이벤트가 저장됨
- [ ] T007 [P] [US1] `src/test/kotlin/com/example/shopbox/delivery/service/DeliveryServiceTest.kt` 신규 작성 — BehaviorSpec, Mockk, 시나리오: `processStockReserved()` 호출 시 `delivery.status = STARTED`로 저장되고 `DeliveryStarted` outbox 이벤트가 저장됨
- [ ] T008 [P] [US1] `src/test/kotlin/com/example/shopbox/order/service/OrderServiceTest.kt` 신규 작성 — BehaviorSpec, Mockk, 시나리오: `processDeliveryStarted(messageId, payload)` 호출 시 `order.status = CONFIRMED`로 업데이트됨 (Fixture Monkey로 Order 픽스처 생성 필요)
- [ ] T009 [US1] `src/test/kotlin/com/example/shopbox/order/controller/OrderControllerTest.kt` 기존 파일 수정 — 정상 Saga 흐름 시나리오 추가: `POST /api/orders` 후 Relay 실행, DeliveryStarted 이벤트 발행까지 체인을 `@SpringBootTest` + Testcontainers(MySQL + Kafka)로 검증

### Implementation for User Story 1 (T005~T009 Red 확인 후)

- [ ] T010 [P] [US1] `src/main/kotlin/com/example/shopbox/payment/service/PaymentService.kt` 수정 — `processOrderCreated()` 내 `paymentRepository.save()` 후 `payment.status = PaymentStatus.COMPLETED` 업데이트 + `paymentRepository.save(payment)` 재호출 추가
- [ ] T011 [P] [US1] `src/main/kotlin/com/example/shopbox/inventory/service/InventoryService.kt` 수정 — `processPaymentCompleted()` 내 `inventoryRepository.save()` 후 `inventory.status = InventoryStatus.RESERVED` 업데이트 + `inventoryRepository.save(inventory)` 재호출 추가
- [ ] T012 [P] [US1] `src/main/kotlin/com/example/shopbox/delivery/service/DeliveryService.kt` 수정 — `processStockReserved()` 내 `deliveryRepository.save()` 후 `delivery.status = DeliveryStatus.STARTED` 업데이트 + `deliveryRepository.save(delivery)` 재호출 추가
- [ ] T013 [US1] `src/main/kotlin/com/example/shopbox/order/service/OrderService.kt` 수정 — `@KafkaListener(topics = [DeliveryStartedEvent.TOPIC], groupId = "shopbox-order-delivery-consumer") fun onDeliveryStarted()` 추가 + `@Transactional fun processDeliveryStarted(messageId, payload)` 구현 (Inbox 멱등성 처리 포함, `order.status = OrderStatus.CONFIRMED`)

**Checkpoint**: `PaymentServiceTest`, `InventoryServiceTest`, `DeliveryServiceTest`, `OrderServiceTest` 전체 통과 + `OrderControllerTest` 정상 흐름 시나리오 통과. US1 독립 검증 완료.

---

## Phase 3: User Story 2 — 결제 실패 보상 (Priority: P2)

**Goal**: Payment 실패 시 `PaymentFailed` 이벤트 발행 + Order 서비스가 이를 수신하여 `Order.status = CANCELLED` + `OrderCancelled` 발행

**Independent Test**: `PaymentServiceTest.processPaymentFailed()` + `OrderServiceTest.processPaymentFailed()` Mockk 단위 테스트로 독립 검증. 보상 핸들러를 직접 호출하여 Kafka 없이 검증 가능.

### Tests for User Story 2 (🔴 Red 먼저 확인)

- [ ] T014 [P] [US2] `src/test/kotlin/com/example/shopbox/payment/service/PaymentServiceTest.kt` 수정 — 시나리오 추가: `processPaymentFailed(messageId, payload)` 호출 시 (1) `payment.status = FAILED`로 저장됨, (2) `outbox_events`에 `PaymentFailed` 이벤트 저장됨, (3) 동일 messageId 재호출 시 중복 처리 skip됨
- [ ] T015 [P] [US2] `src/test/kotlin/com/example/shopbox/order/service/OrderServiceTest.kt` 수정 — 시나리오 추가: `processPaymentEvent(messageId, payload)` 호출 시 `eventType = "PaymentFailed"`면 (1) `order.status = CANCELLED`로 업데이트됨, (2) `outbox_events`에 `OrderCancelled` 이벤트 저장됨

### Implementation for User Story 2 (T014~T015 Red 확인 후)

- [ ] T016 [P] [US2] `src/main/kotlin/com/example/shopbox/order/event/OrderCancelledEvent.kt` 신규 생성 — `CompensationEvent` 구현, `reason: String`, `orderId: Long`, companion object에 `EVENT_TYPE = "OrderCancelled"`, `AGGREGATE_TYPE = "Order"`, `TOPIC = "order-events"`
- [ ] T017 [P] [US2] `src/main/kotlin/com/example/shopbox/payment/event/PaymentFailedEvent.kt` 신규 생성 — `CompensationEvent` 구현, `reason: String`, `paymentId: Long`, `orderId: Long`, companion object에 `EVENT_TYPE = "PaymentFailed"`, `AGGREGATE_TYPE = "Payment"`, `TOPIC = "payment-events"`
- [ ] T018 [US2] `src/main/kotlin/com/example/shopbox/payment/service/PaymentService.kt` 수정 — `@Transactional fun processPaymentFailed(messageId: String, payload: String)` 추가 — Inbox 처리, `payment.status = FAILED`, `outbox_events`에 `PaymentFailedEvent` 저장 (T017 완료 후)
- [ ] T019 [US2] `src/main/kotlin/com/example/shopbox/order/service/OrderService.kt` 수정 — `@KafkaListener(topics = [PaymentFailedEvent.TOPIC], groupId = "shopbox-order-saga-consumer") fun onPaymentEvent()` 추가 + `@Transactional fun processPaymentEvent(messageId, payload)` 구현 — `eventType` 분기: `PaymentFailed` → `order.status = CANCELLED` + `OrderCancelledEvent` outbox 저장 (T016, T017 완료 후)

**Checkpoint**: `PaymentServiceTest` + `OrderServiceTest` US2 시나리오 전체 통과. US2 독립 검증 완료.

---

## Phase 4: User Story 3 — 재고 부족 보상 (Priority: P3)

**Goal**: 재고 부족 시 `StockReservationFailed` 발행 → Payment `REFUNDED` + `PaymentRefunded` 발행 → Order `CANCELLED` + `OrderCancelled` 발행

**Independent Test**: `InventoryServiceTest.processStockReservationFailed()` + `PaymentServiceTest.processInventoryEvent()` + `OrderServiceTest.processPaymentEvent()` Mockk 단위 테스트. US2의 OrderService 핸들러에 `PaymentRefunded` 분기만 추가.

### Tests for User Story 3 (🔴 Red 먼저 확인)

- [ ] T020 [P] [US3] `src/test/kotlin/com/example/shopbox/inventory/service/InventoryServiceTest.kt` 수정 — 시나리오 추가: `processStockReservationFailed(messageId, payload)` 호출 시 (1) `inventory.status = FAILED`로 저장됨, (2) `outbox_events`에 `StockReservationFailed` 이벤트 저장됨
- [ ] T021 [P] [US3] `src/test/kotlin/com/example/shopbox/payment/service/PaymentServiceTest.kt` 수정 — 시나리오 추가: `processInventoryEvent(messageId, payload)` 호출 시 `eventType = "StockReservationFailed"`면 (1) `payment.status = REFUNDED`로 업데이트됨, (2) `outbox_events`에 `PaymentRefunded` 이벤트 저장됨
- [ ] T022 [P] [US3] `src/test/kotlin/com/example/shopbox/order/service/OrderServiceTest.kt` 수정 — 시나리오 추가: `processPaymentEvent(messageId, payload)` 호출 시 `eventType = "PaymentRefunded"`면 (1) `order.status = CANCELLED`로 업데이트됨, (2) `outbox_events`에 `OrderCancelled` 이벤트 저장됨

### Implementation for User Story 3 (T020~T022 Red 확인 후)

- [ ] T023 [P] [US3] `src/main/kotlin/com/example/shopbox/inventory/event/StockReservationFailedEvent.kt` 신규 생성 — `CompensationEvent` 구현, `reason: String`, `orderId: Long`, `productId: Long`, `quantity: Int`, companion object에 `EVENT_TYPE = "StockReservationFailed"`, `AGGREGATE_TYPE = "Inventory"`, `TOPIC = "inventory-events"`
- [ ] T024 [P] [US3] `src/main/kotlin/com/example/shopbox/payment/event/PaymentRefundedEvent.kt` 신규 생성 — `CompensationEvent` 구현, `reason: String`, `paymentId: Long`, `orderId: Long`, companion object에 `EVENT_TYPE = "PaymentRefunded"`, `AGGREGATE_TYPE = "Payment"`, `TOPIC = "payment-events"`
- [ ] T025 [US3] `src/main/kotlin/com/example/shopbox/inventory/service/InventoryService.kt` 수정 — `@Transactional fun processStockReservationFailed(messageId: String, payload: String)` 추가 — Inbox 처리, `inventory.status = FAILED`, `outbox_events`에 `StockReservationFailedEvent` 저장 (T023 완료 후)
- [ ] T026 [US3] `src/main/kotlin/com/example/shopbox/payment/service/PaymentService.kt` 수정 — `@KafkaListener(topics = [StockReservationFailedEvent.TOPIC], groupId = "shopbox-payment-saga-consumer") fun onInventoryEvent()` 추가 + `@Transactional fun processInventoryEvent(messageId, payload)` 구현 — `eventType` 분기: `StockReservationFailed` → `payment.status = REFUNDED` + `PaymentRefundedEvent` outbox 저장 (T024 완료 후)
- [ ] T027 [US3] `src/main/kotlin/com/example/shopbox/order/service/OrderService.kt` 수정 — `processPaymentEvent()` 내 `PaymentRefunded` 분기 추가 — `order.status = CANCELLED` + `OrderCancelledEvent` outbox 저장 (T019, T024 완료 후)

**Checkpoint**: `InventoryServiceTest` + `PaymentServiceTest` + `OrderServiceTest` US3 시나리오 전체 통과. US3 독립 검증 완료.

---

## Phase 5: User Story 4 — 배송 실패 보상 (Priority: P4)

**Goal**: 배송 실패 시 `DeliveryFailed` 발행 → 재고 복구 `RESTORED` + `StockRestored` 발행 → Payment `REFUNDED` + `PaymentRefunded` 발행 → Order `CANCELLED`

**Independent Test**: `DeliveryServiceTest.processDeliveryFailed()` + `InventoryServiceTest.processDeliveryFailed()` + US3에서 검증된 `PaymentService.processInventoryEvent(StockRestored)` 분기 추가 Mockk 테스트.

### Tests for User Story 4 (🔴 Red 먼저 확인)

- [ ] T028 [P] [US4] `src/test/kotlin/com/example/shopbox/delivery/service/DeliveryServiceTest.kt` 수정 — 시나리오 추가: `processDeliveryFailed(messageId, payload)` 호출 시 (1) `delivery.status = FAILED`로 저장됨, (2) `outbox_events`에 `DeliveryFailed` 이벤트 저장됨
- [ ] T029 [P] [US4] `src/test/kotlin/com/example/shopbox/inventory/service/InventoryServiceTest.kt` 수정 — 시나리오 추가: `processDeliveryEvent(messageId, payload)` 호출 시 `eventType = "DeliveryFailed"`면 (1) `inventory.status = RESTORED`로 업데이트됨, (2) Stock 복구됨, (3) `outbox_events`에 `StockRestored` 이벤트 저장됨
- [ ] T030 [P] [US4] `src/test/kotlin/com/example/shopbox/payment/service/PaymentServiceTest.kt` 수정 — 시나리오 추가: `processInventoryEvent(messageId, payload)` 호출 시 `eventType = "StockRestored"`면 (1) `payment.status = REFUNDED`로 업데이트됨, (2) `outbox_events`에 `PaymentRefunded` 이벤트 저장됨

### Implementation for User Story 4 (T028~T030 Red 확인 후)

- [ ] T031 [P] [US4] `src/main/kotlin/com/example/shopbox/delivery/event/DeliveryFailedEvent.kt` 신규 생성 — `CompensationEvent` 구현, `reason: String`, `deliveryId: Long`, `orderId: Long`, companion object에 `EVENT_TYPE = "DeliveryFailed"`, `AGGREGATE_TYPE = "Delivery"`, `TOPIC = "delivery-events"`
- [ ] T032 [P] [US4] `src/main/kotlin/com/example/shopbox/inventory/event/StockRestoredEvent.kt` 신규 생성 — `CompensationEvent` 구현, `reason: String`, `inventoryId: Long`, `orderId: Long`, `productId: Long`, `quantity: Int`, companion object에 `EVENT_TYPE = "StockRestored"`, `AGGREGATE_TYPE = "Inventory"`, `TOPIC = "inventory-events"`
- [ ] T033 [US4] `src/main/kotlin/com/example/shopbox/delivery/service/DeliveryService.kt` 수정 — `@Transactional fun processDeliveryFailed(messageId: String, payload: String)` 추가 — Inbox 처리, `delivery.status = FAILED`, `outbox_events`에 `DeliveryFailedEvent` 저장 (T031 완료 후)
- [ ] T034 [US4] `src/main/kotlin/com/example/shopbox/inventory/service/InventoryService.kt` 수정 — `@KafkaListener(topics = [DeliveryFailedEvent.TOPIC], groupId = "shopbox-inventory-saga-consumer") fun onDeliveryEvent()` 추가 + `@Transactional fun processDeliveryEvent(messageId, payload)` 구현 — `eventType` 분기: `DeliveryFailed` → Stock 복구 (`inventoryLockStrategy` 역방향), `inventory.status = RESTORED`, `outbox_events`에 `StockRestoredEvent` 저장 (T032 완료 후)
- [ ] T035 [US4] `src/main/kotlin/com/example/shopbox/payment/service/PaymentService.kt` 수정 — `processInventoryEvent()` 내 `StockRestored` 분기 추가 — `payment.status = REFUNDED` + `PaymentRefundedEvent` outbox 저장 (T026, T032 완료 후)

**Checkpoint**: `DeliveryServiceTest` + `InventoryServiceTest` + `PaymentServiceTest` US4 시나리오 전체 통과. US4 독립 검증 완료.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 멱등성 검증 + `MessageRelay.topicFor()` 보상 이벤트 토픽 매핑 확인

- [ ] T036 [P] `src/main/kotlin/com/example/shopbox/outbox/relay/MessageRelay.kt` 수정 — `topicFor()` 내 보상 이벤트 Aggregate Type 매핑 추가 확인 (이미 `else -> "$aggregateType-events".lowercase()` fallback으로 커버되는지 검증, 미커버 시 명시적 매핑 추가)
- [ ] T037 [P] `src/test/kotlin/com/example/shopbox/order/service/OrderServiceTest.kt` 수정 — 멱등성 시나리오 추가: 동일 `messageId`로 `processPaymentEvent()` 재호출 시 `OrderCancelled`가 두 번 저장되지 않음 검증
- [ ] T038 [P] `src/test/kotlin/com/example/shopbox/payment/service/PaymentServiceTest.kt` 수정 — 멱등성 시나리오 추가: 동일 `messageId`로 `processInventoryEvent()` 재호출 시 `PaymentRefunded`가 두 번 저장되지 않음 검증

**Checkpoint**: 전체 `./gradlew test` 통과 확인. `OrderControllerTest` Saga 정상 흐름 통합 테스트 통과 포함.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundation (Phase 1)**: 즉시 시작 가능 — T002~T004 병렬 실행 가능
- **US1 (Phase 2)**: Phase 1 완료 후 시작 — T005~T009 테스트 병렬, T010~T012 구현 병렬
- **US2 (Phase 3)**: Phase 1 완료 후 시작 가능 (US1과 독립) — T016~T017 병렬
- **US3 (Phase 4)**: Phase 1 + US2 완료 후 시작 (`OrderCancelledEvent` 재사용) — T023~T024 병렬
- **US4 (Phase 5)**: Phase 1 + US3 완료 후 시작 (`PaymentRefundedEvent` 재사용) — T031~T032 병렬
- **Polish (Phase 6)**: 모든 US 완료 후

### User Story Dependencies

```
Phase 1 (Foundation)
  ├── US1 (독립)
  ├── US2 (독립)
  │     └── US3 (OrderCancelledEvent 의존)
  │           └── US4 (PaymentRefundedEvent 의존)
  └── US3 시작 가능: Phase 1 + US2 완료 후
```

### Within Each User Story

1. 🔴 Red: 테스트 작성 → `./gradlew test` 실패 확인
2. 이벤트 클래스 신규 생성 (병렬 가능)
3. 🟢 Green: 서비스 구현 → `./gradlew test` 통과 확인
4. 🔵 Refactor: 코드 정리 → `./gradlew test` 재통과 확인

---

## Parallel Opportunities

### Phase 1 병렬 실행

```
T002 (OrderStatus) || T003 (PaymentStatus) || T004 (InventoryStatus)
```

### US1 병렬 실행

```
# 테스트 작성 병렬
T005 (PaymentServiceTest) || T006 (InventoryServiceTest) || T007 (DeliveryServiceTest) || T008 (OrderServiceTest)

# 구현 병렬
T010 (PaymentService) || T011 (InventoryService) || T012 (DeliveryService)
```

### US2 병렬 실행

```
T014 (PaymentServiceTest 수정) || T015 (OrderServiceTest 수정)
T016 (OrderCancelledEvent) || T017 (PaymentFailedEvent)
```

### US3 병렬 실행

```
T020 (InventoryServiceTest) || T021 (PaymentServiceTest) || T022 (OrderServiceTest)
T023 (StockReservationFailedEvent) || T024 (PaymentRefundedEvent)
```

### US4 병렬 실행

```
T028 (DeliveryServiceTest) || T029 (InventoryServiceTest) || T030 (PaymentServiceTest)
T031 (DeliveryFailedEvent) || T032 (StockRestoredEvent)
```

---

## Implementation Strategy

### MVP First (US1만)

1. Phase 1 완료 (enum 확장 + CompensationEvent)
2. Phase 2 (US1): 정상 흐름 status 업데이트 + OrderService.onDeliveryStarted()
3. **STOP and VALIDATE**: OrderControllerTest 정상 Saga 흐름 통과
4. 이후 US2~US4 순차 추가

### Incremental Delivery

- US1 완료 → 정상 Saga 흐름 검증 (4개 서비스 상태 업데이트 확인)
- US2 추가 → 결제 실패 보상 검증 (1단계 체인)
- US3 추가 → 재고 부족 보상 검증 (2단계 체인)
- US4 추가 → 배송 실패 보상 검증 (3단계 체인, 전체 Saga 완성)

---

## Notes

- 보상 핸들러(`processPaymentFailed`, `processDeliveryFailed` 등)는 **public 메서드**로 선언하여 테스트에서 직접 호출 가능하게 함 (research Decision 5)
- `onPaymentEvent()` / `onInventoryEvent()` 내부에서 `eventType`으로 분기: 동일 토픽에 여러 이벤트 타입 혼재 (research Decision 7)
- 모든 보상 핸들러는 기존과 동일하게 `inboxEventRepository.saveIfAbsent(messageId)` 멱등성 처리 포함
- `Delivery.status`는 이미 `FAILED` 값 존재 (추가 enum 변경 불필요)
- Stock 복구 (T034): `inventoryLockStrategy`의 역방향 적용 — 구체적 구현은 기존 `deductStock()`의 반대 연산 (`restoreStock()` 추가 검토)
