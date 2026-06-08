# Tasks: Transactional Outbox / Inbox / Message Relay

**Input**: Design documents from `/specs/001-outbox-inbox-relay/`
**Prerequisites**: plan.md ✅ spec.md ✅ research.md ✅ data-model.md ✅ contracts/ ✅

> **Tests**: Constitution §I mandates TDD — Red→Green→Refactor with `./gradlew test` at every step.
> Tests are **MANDATORY**, not optional. Only two test types are permitted (Constitution §II):
> `{Domain}ServiceTest` (Mockk) · `{Domain}ControllerTest` (Testcontainers)

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: Can run in parallel (different files, no interdependencies)
- **[Story]**: User story this task belongs to (US1 / US2 / US3)
- **Red / Green / Refactor** labels follow the TDD cycle

---

## Phase 1: Setup

**Purpose**: 빌드 의존성·설정 파일 준비 — 이후 모든 Phase의 전제 조건

- [x] T001 Add missing dependencies to `build.gradle.kts`: `spring-kafka`, `kotest-runner-junit5:5.9.1`, `kotest-extensions-spring:1.3.0`, `mockk:1.13.17`, `spring-boot-testcontainers`, `testcontainers:kafka`, `testcontainers:mysql`
- [x] T002 [P] Add Kafka consumer/producer config and `outbox.relay.interval` property to `src/main/resources/application.yaml`
- [x] T003 [P] Create Testcontainers base config `src/test/kotlin/com/example/shopbox/TestContainersConfig.kt` — shared `@SpringBootTest` parent with MySQL + Kafka containers

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 User Story가 의존하는 공통 인프라 — Phase 3 이전에 완료 필수

**⚠️ CRITICAL**: 이 Phase가 끝나기 전까지 어떤 User Story도 시작 불가

- [x] T004 Create `src/main/kotlin/com/example/shopbox/common/entity/BaseEntity.kt` with `createdAt`, `updatedAt`, `deletedAt` fields (`@MappedSuperclass`)
- [x] T005 [P] Create `src/main/kotlin/com/example/shopbox/common/event/DomainEvent.kt` interface with `eventId`, `eventType`, `occurredAt`
- [x] T006 [P] Create `src/main/kotlin/com/example/shopbox/common/dto/response/ApiResponse.kt` wrapper
- [x] T007 [P] Create `src/main/kotlin/com/example/shopbox/common/exception/BusinessException.kt` and `GlobalExceptionHandler.kt` with `@RestControllerAdvice`
- [x] T008 Create `src/main/kotlin/com/example/shopbox/outbox/entity/OutboxEvent.kt` and `src/main/kotlin/com/example/shopbox/outbox/repository/OutboxEventRepository.kt` (append-only, no BaseEntity)
- [x] T009 [P] Create `src/main/kotlin/com/example/shopbox/inbox/entity/InboxEvent.kt` and `src/main/kotlin/com/example/shopbox/inbox/repository/InboxEventRepository.kt` (PK = `messageId`, append-only)

**Checkpoint**: 공통 인프라 완료 — User Story 구현 시작 가능

---

## Phase 3: User Story 1 — 원자적 주문 + Outbox 저장 (Priority: P1) 🎯 MVP

**Goal**: 주문 생성 요청 1회에 `orders` + `outbox_events` 가 같은 트랜잭션으로 저장되거나 둘 다 롤백됨

**Independent Test**: `POST /api/orders` 호출 후 `orders`, `outbox_events` 양쪽 레코드 존재 확인; `outbox_events` 저장 실패 시뮬레이션으로 주문 롤백 확인

### Tests — US1

- [x] T010 [US1] **Red** — Write `src/test/kotlin/com/example/shopbox/order/OrderServiceTest.kt` (Mockk + Kotest BehaviorSpec): (1) createOrder → Order + OutboxEvent 동시 저장 검증, (2) OutboxEvent 저장 실패 시 전체 롤백 검증. `./gradlew test` 실행 → FAIL 확인

### Implementation — US1

- [x] T011 [P] [US1] Create `src/main/kotlin/com/example/shopbox/order/entity/Order.kt` (BaseEntity 상속, `@SQLDelete`, `@SQLRestriction`, `@Tsid` PK) and `src/main/kotlin/com/example/shopbox/order/domain/enums/OrderStatus.kt` (PENDING / PAID / CANCELLED)
- [x] T012 [P] [US1] Create `src/main/kotlin/com/example/shopbox/order/repository/OrderRepository.kt` (Spring Data JPA)
- [x] T013 [P] [US1] Create `src/main/kotlin/com/example/shopbox/order/event/OrderCreatedEvent.kt` implementing `DomainEvent`
- [x] T014 [US1] **Green** — Implement `src/main/kotlin/com/example/shopbox/order/service/OrderService.kt`: `@Transactional createOrder()` saves `Order` then `OutboxEvent` in single transaction. `./gradlew test` → OrderServiceTest PASS
- [x] T015 [US1] **Red** — Write `src/test/kotlin/com/example/shopbox/order/OrderControllerTest.kt` (Testcontainers + Kotest BehaviorSpec): `POST /api/orders` 성공 시 두 테이블 레코드 확인, `outbox_events.processed_at IS NULL` 검증. `./gradlew test` → FAIL 확인
- [x] T016 [P] [US1] Create `src/main/kotlin/com/example/shopbox/order/dto/request/CreateOrderRequest.kt` (Spring Validation, `@field:NotNull`) and `src/main/kotlin/com/example/shopbox/order/dto/response/OrderResponse.kt`
- [x] T017 [US1] **Green** — Implement `src/main/kotlin/com/example/shopbox/order/controller/OrderController.kt`: `POST /api/orders` endpoint, `@Valid` on request, `ApiResponse` wrapper. `./gradlew test` → OrderControllerTest PASS
- [x] T018 [US1] **Refactor** — Clean up `order/` package (naming, trailing commas per convention), run `./gradlew test` → still PASS

**Checkpoint**: US1 독립 검증 가능 — `orders` + `outbox_events` 원자성 확인됨

---

## Phase 4: User Story 2 — 멱등 Inbox 처리 (Priority: P2)

**Goal**: 동일 `message_id`의 Kafka 메시지가 재전송되어도 결제 비즈니스 로직이 정확히 1회만 실행됨

**Independent Test**: 동일 `message_id`를 가진 `OrderCreated` 이벤트를 Kafka 토픽에 2회 발행 → `payments` 레코드 1건, `inbox_events` 레코드 1건만 존재 확인

### Tests — US2

- [x] T019 [US2] **Red** — Write `src/test/kotlin/com/example/shopbox/payment/PaymentServiceTest.kt` (Mockk + Kotest BehaviorSpec): (1) 신규 `message_id` → 결제 처리 + InboxEvent 저장, (2) 중복 `message_id` → 비즈니스 로직 skip, (3) 처리 중 오류 → InboxEvent 미저장. `./gradlew test` → FAIL 확인

### Implementation — US2

- [x] T020 [P] [US2] Create `src/main/kotlin/com/example/shopbox/payment/entity/Payment.kt` (BaseEntity 상속, `@Tsid` PK) and `src/main/kotlin/com/example/shopbox/payment/domain/enums/PaymentStatus.kt` (PENDING / COMPLETED / FAILED)
- [x] T021 [P] [US2] Create `src/main/kotlin/com/example/shopbox/payment/repository/PaymentRepository.kt`
- [x] T022 [P] [US2] Create `src/main/kotlin/com/example/shopbox/payment/event/PaymentCompletedEvent.kt` implementing `DomainEvent`
- [x] T023 [US2] **Green** — Implement `src/main/kotlin/com/example/shopbox/payment/service/PaymentService.kt`: `@KafkaListener(topics = ["order-events"])`, `@Transactional` with `InboxEventRepository.save()` → catch `DataIntegrityViolationException` for idempotency. `./gradlew test` → PaymentServiceTest PASS
- [x] T024 [US2] **Red** — Write `src/test/kotlin/com/example/shopbox/payment/PaymentControllerTest.kt` (Testcontainers + Kotest BehaviorSpec): `order-events` 토픽에 동일 메시지 2회 발행 → DB에서 `inbox_events` 중복 없음, `payments` 1건 확인. `./gradlew test` → FAIL 확인
- [x] T025 [P] [US2] Create minimal Inventory bounded context: `inventory/entity/Inventory.kt`, `inventory/domain/enums/InventoryStatus.kt`, `inventory/repository/InventoryRepository.kt`, `inventory/service/InventoryService.kt` (`@KafkaListener(topics = ["payment-events"])`, inbox 패턴 동일 적용), `inventory/event/StockReservedEvent.kt`
- [x] T026 [P] [US2] Create minimal Delivery bounded context: `delivery/entity/Delivery.kt`, `delivery/domain/enums/DeliveryStatus.kt`, `delivery/repository/DeliveryRepository.kt`, `delivery/service/DeliveryService.kt` (`@KafkaListener(topics = ["inventory-events"])`, inbox 패턴 동일 적용), `delivery/event/DeliveryStartedEvent.kt`
- [x] T027 [US2] **Green** — Implement `src/main/kotlin/com/example/shopbox/payment/controller/PaymentController.kt` (status 조회 placeholder). `./gradlew test` → PaymentControllerTest PASS
- [x] T028 [US2] **Refactor** — Clean up `payment/`, `inventory/`, `delivery/` packages, run `./gradlew test` → still PASS

**Checkpoint**: US2 독립 검증 가능 — 동일 메시지 재전송 시 중복 처리 0건 확인됨

---

## Phase 5: User Story 3 — 주기적 Message Relay (Priority: P3)

**Goal**: `outbox_events` 폴링 → Kafka 발행 → `processed_at` 업데이트; Kafka 장애 시 자동 재시도

**Independent Test**: `outbox_events` N건 삽입 → Relay 실행 → Kafka `order-events` 토픽 N건 확인 + `processed_at NOT NULL` 확인; Kafka 장애 mock → `processed_at` 그대로 null

### Tests — US3

- [x] T029 [US3] **Red** — Write `src/test/kotlin/com/example/shopbox/outbox/MessageRelayTest.kt` (Mockk + Kotest BehaviorSpec): (1) 미처리 N건 → 전부 발행 + `processed_at` 업데이트, (2) Kafka 오류 → `processed_at` null 유지, (3) 미처리 0건 → no-op. `./gradlew test` → FAIL 확인

### Implementation — US3

- [x] T030 [US3] **Green** — Implement `src/main/kotlin/com/example/shopbox/outbox/relay/MessageRelay.kt`: `@Scheduled(fixedDelayString = "\${outbox.relay.interval:5000}")`, `OutboxEventRepository.findByProcessedAtIsNull()`, `KafkaTemplate.send().get()` (sync), 성공 시 `processedAt` 업데이트. `./gradlew test` → MessageRelayTest PASS
- [x] T031 [US3] Add `@EnableScheduling` to `src/main/kotlin/com/example/shopbox/ShopboxApplication.kt`
- [x] T032 [US3] **Red** — Write end-to-end test section in `src/test/kotlin/com/example/shopbox/order/OrderControllerTest.kt` (Testcontainers): `POST /api/orders` → relay 실행 대기 → `order-events` 소비 → `inbox_events` 저장 확인 (전체 흐름). `./gradlew test` → FAIL 확인
- [x] T033 [US3] **Green** — Wire `KafkaTemplate<String, String>` Bean 및 `OutboxEventRepository.findByProcessedAtIsNull()` 쿼리 메서드 확인, topic 라우팅 로직(`aggregate_type` → topic name) 구현. `./gradlew test` → OrderControllerTest 전체 PASS
- [x] T034 [US3] **Refactor** — Relay 로직 정리 (단일 책임), run `./gradlew test` → still PASS

**Checkpoint**: US3 독립 검증 가능 — 전체 이벤트 체인 자동 동작 확인됨

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T035 [P] Write `src/test/kotlin/com/example/shopbox/inventory/InventoryServiceTest.kt` and `src/test/kotlin/com/example/shopbox/delivery/DeliveryServiceTest.kt` (Mockk) — inbox 중복 처리 엣지 케이스 검증
- [x] T036 [P] Verify all spec.md acceptance scenarios covered: SC-001 원자성, SC-002 중복 처리율 0%, SC-003 자동 재발행, SC-004 설정 변경, SC-005 Testcontainers 전체 흐름
- [x] T037 Run `./gradlew test` — final green gate, 전체 테스트 통과 확인

---

## Dependencies & Execution Order

### Phase Dependencies

```
Phase 1 (Setup)
    └─→ Phase 2 (Foundational)  ← BLOCKS everything below
            ├─→ Phase 3 (US1/P1)  🎯 MVP
            │       └─→ Phase 4 (US2/P2)
            │               └─→ Phase 5 (US3/P3)
            │                       └─→ Phase 6 (Polish)
            └─[parallel possible if team > 1]
```

### User Story Dependencies

| Story | Depends on | Note |
|-------|------------|------|
| US1 (P1) | Phase 2 완료 | 독립 구현·테스트 가능 |
| US2 (P2) | Phase 2 완료 | US1 없이도 Kafka 직접 publish로 테스트 가능 |
| US3 (P3) | Phase 2 완료 | OutboxEvent 직접 삽입으로 독립 테스트 가능 |

### Within Each User Story

```
Red (ServiceTest) → 엔티티/레포/이벤트 생성 → Green (Service 구현)
    → Red (ControllerTest) → DTO 생성 → Green (Controller 구현) → Refactor
```

### Parallel Opportunities

- Phase 1: T002, T003 → T001과 병렬
- Phase 2: T005, T006, T007, T009 → 서로 병렬 (T004 완료 후)
- Phase 3: T011, T012, T013 → T010 작성 직후 병렬; T016은 T015와 병렬
- Phase 4: T020, T021, T022, T025, T026 → 병렬; T025, T026은 T023 완료 전 선행 가능
- Phase 6: T035, T036 → 병렬

---

## Parallel Example: User Story 1

```
[T010 작성 완료 후]

병렬 실행 가능:
├── T011: Order entity + OrderStatus enum
├── T012: OrderRepository
└── T013: OrderCreatedEvent

↓ T011~T013 완료 후

T014: OrderService 구현 (Green)

[T015 작성 완료 후]

병렬 실행 가능:
└── T016: CreateOrderRequest + OrderResponse DTO

↓ T016 완료 후

T017: OrderController 구현 (Green)
T018: Refactor
```

---

## Implementation Strategy

### MVP First (US1만 완료)

1. Phase 1 Setup 완료
2. Phase 2 Foundational 완료 (CRITICAL)
3. Phase 3 (US1) 완료
4. **STOP & VALIDATE**: `POST /api/orders` → DB 양쪽 확인

### Incremental Delivery

```
Phase 1+2 → Foundation ✅
Phase 3    → US1 완료 → orders + outbox_events 원자성 검증 (MVP)
Phase 4    → US2 완료 → Kafka 수신 멱등성 검증
Phase 5    → US3 완료 → 전체 이벤트 체인 자동화 검증
Phase 6    → Polish
```

---

## Notes

- `[P]` = 다른 파일 작업, 완료된 작업에 의존하지 않음
- TDD 순서 **엄수**: Red `./gradlew test` FAIL 확인 → 구현 → Green `./gradlew test` PASS 확인 → Refactor `./gradlew test` PASS 확인
- `RepositoryTest`, `EntityTest`, `DTOTest` 작성 금지 (Constitution §II)
- 파라미터 선언은 1줄 1개, trailing comma 필수 (Constitution Coding Convention)
- OutboxEvent, InboxEvent는 `BaseEntity` 상속 없음 (append-only)
- `@SQLDelete`, `@SQLRestriction`은 Order, Payment, Inventory, Delivery 각각 선언
