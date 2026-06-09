# Tasks: Concurrency Control

**Input**: Design documents from `specs/002-concurrency-control/`
**Prerequisites**: plan.md ✅ spec.md ✅ research.md ✅ data-model.md ✅ contracts/ ✅

**TDD**: All user story phases follow Red → Green → Refactor. Interface is created first (enables test compilation), then failing tests are written (Red), then implementations make them pass (Green).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 실행 가능 (파일이 다르고 미완료 태스크에 의존하지 않음)
- **[Story]**: 해당 태스크가 속하는 User Story (US1, US2, US3)
- 경로 기준: `src/main/kotlin/com/example/shopbox/` → `main/...`, `src/test/kotlin/com/example/shopbox/` → `test/...`

---

## Phase 1: Setup

**Purpose**: 신규 의존성 추가 및 설정 파일 준비

- [X] T001 [P] `redisson-spring-boot-starter:4.5.0` 및 `testcontainers:redis` 의존성을 `build.gradle.kts`에 추가
- [X] T002 [P] `order.lock-strategy`, `inventory.lock-strategy`, `spring.data.redis` 설정을 `src/main/resources/application.yml`에 추가

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 User Story가 공유하는 인프라 — 완료 전까지 US 작업 시작 불가

**⚠️ CRITICAL**: 이 Phase가 끝나야 US1·US2·US3 작업을 시작할 수 있다

- [X] T003 `@DistributedLock` 어노테이션 생성: `main/common/lock/DistributedLock.kt`
- [X] T004 `DistributedLockAspect` 구현 (T003 완료 후): `main/common/lock/DistributedLockAspect.kt` — SpEL 키 파싱, `@Order(Ordered.LOWEST_PRECEDENCE - 1)`, tryLock/unlock
- [X] T005 [P] `Stock` 엔티티 생성 (`@Version`, `deduct()`, Soft Delete): `main/inventory/entity/Stock.kt`
- [X] T006 `StockRepository` 생성 (`findByProductId`, `findByProductIdForUpdate` with `@Lock`): `main/inventory/repository/StockRepository.kt`
- [X] T007 [P] `Order` 엔티티에 `(userId, productId)` 복합 unique 제약 추가: `main/order/entity/Order.kt`

**Checkpoint**: Foundation 완료 — US1·US2·US3 병렬 시작 가능

---

## Phase 3: User Story 1 — 주문 생성 동시성 제어 (Priority: P1) 🎯 MVP

**Goal**: 동일 사용자/상품 조합으로 N개 스레드가 동시 주문 시 정확히 1건만 성공하도록 세 전략 모두 보장한다

**Independent Test**: `./gradlew test --tests "*.strategy.Order*LockTest"` — 각 전략 테스트가 `successCount == 1` 검증

### Interface (enables test compilation)

- [X] T008 [US1] `OrderLockStrategy` 인터페이스 생성: `main/order/service/strategy/OrderLockStrategy.kt`

### Tests (Red — 구현 전 반드시 실패 확인)

- [X] T009 [P] [US1] `OrderOptimisticLockTest` 작성 (50 스레드, successCount == 1 검증): `test/order/service/strategy/OrderOptimisticLockTest.kt`
- [X] T010 [P] [US1] `OrderPessimisticLockTest` 작성 (50 스레드, successCount == 1 검증): `test/order/service/strategy/OrderPessimisticLockTest.kt`
- [X] T011 [P] [US1] `OrderDistributedLockTest` 작성 (50 스레드, successCount == 1 검증): `test/order/service/strategy/OrderDistributedLockTest.kt`

### Implementation (Green)

- [X] T012 [P] [US1] `OrderOptimisticLockStrategy` 구현 (`@ConditionalOnProperty(havingValue="optimistic")`, 재시도 3회): `main/order/service/strategy/OrderOptimisticLockStrategy.kt`
- [X] T013 [P] [US1] `OrderPessimisticLockStrategy` 구현 (`@ConditionalOnProperty(havingValue="pessimistic")`, SELECT FOR UPDATE): `main/order/service/strategy/OrderPessimisticLockStrategy.kt`
- [X] T014 [P] [US1] `OrderDistributedLockStrategy` 구현 (`@ConditionalOnProperty(havingValue="distributed")`, `@DistributedLock`): `main/order/service/strategy/OrderDistributedLockStrategy.kt`
- [X] T015 [US1] `OrderService`가 주입된 `OrderLockStrategy`에 주문 생성을 위임하도록 수정: `main/order/service/OrderService.kt`

**Checkpoint**: US1 완료 — `./gradlew test --tests "*.strategy.Order*LockTest"` 모두 Green

---

## Phase 4: User Story 2 — 재고 차감 동시성 제어 (Priority: P2)

**Goal**: 재고 100개 상품에 200 스레드 동시 차감 시 정확히 100건만 성공하고 재고가 0 미만이 되지 않도록 세 전략 모두 보장한다

**Independent Test**: `./gradlew test --tests "*.strategy.Inventory*LockTest"` — 각 전략 테스트가 `successCount == 100` 및 `stock.quantity >= 0` 검증

### Interface (enables test compilation)

- [X] T016 [US2] `InventoryLockStrategy` 인터페이스 생성: `main/inventory/service/strategy/InventoryLockStrategy.kt`

### Tests (Red — 구현 전 반드시 실패 확인)

- [X] T017 [P] [US2] `InventoryOptimisticLockTest` 작성 (200 스레드, quantity >= 0 검증): `test/inventory/service/strategy/InventoryOptimisticLockTest.kt`
- [X] T018 [P] [US2] `InventoryPessimisticLockTest` 작성 (200 스레드, successCount == 100 검증): `test/inventory/service/strategy/InventoryPessimisticLockTest.kt`
- [X] T019 [P] [US2] `InventoryDistributedLockTest` 작성 (200 스레드, successCount == 100 검증): `test/inventory/service/strategy/InventoryDistributedLockTest.kt`

### Implementation (Green)

- [X] T020 [P] [US2] `InventoryOptimisticLockStrategy` 구현 (`@ConditionalOnProperty(havingValue="optimistic")`, @Version 재시도): `main/inventory/service/strategy/InventoryOptimisticLockStrategy.kt`
- [X] T021 [P] [US2] `InventoryPessimisticLockStrategy` 구현 (`@ConditionalOnProperty(havingValue="pessimistic")`, `findByProductIdForUpdate`): `main/inventory/service/strategy/InventoryPessimisticLockStrategy.kt`
- [X] T022 [P] [US2] `InventoryDistributedLockStrategy` 구현 (`@ConditionalOnProperty(havingValue="distributed")`, `@DistributedLock`): `main/inventory/service/strategy/InventoryDistributedLockStrategy.kt`
- [X] T023 [US2] `InventoryService`가 Kafka Consumer 흐름 내에서 `InventoryLockStrategy.deductStock()`을 호출하도록 수정: `main/inventory/service/InventoryService.kt`

**Checkpoint**: US2 완료 — `./gradlew test --tests "*.strategy.Inventory*LockTest"` 모두 Green

---

## Phase 5: User Story 3 — 락 전략 성능 비교 (Priority: P3)

**Goal**: 동일 부하 조건(재고 100, 스레드 200)에서 세 전략을 순차 실행해 처리 시간(ms), TPS, 성공/실패 건수를 수치로 출력한다

**Independent Test**: `./gradlew test --tests "*.strategy.InventoryLockPerformanceTest"` — 테스트 로그에 세 전략의 측정 결과 출력 확인

- [X] T024 [US3] `InventoryLockPerformanceTest` 작성 — 세 전략을 각각 실행하며 처리 시간·성공/실패 건수·TPS를 측정 후 `logger.info()` 출력: `test/inventory/service/strategy/InventoryLockPerformanceTest.kt`

**Checkpoint**: US3 완료 — 테스트 로그에 세 전략 측정 결과 수치 확인

---

## Phase 6: Polish

**Purpose**: 전체 통합 검증

- [X] T025 `./gradlew test` 전체 실행 — 기존 Outbox/Inbox/Relay 테스트 포함 전체 Green 확인

---

## Dependencies & Execution Order

### Phase Dependencies

```
Phase 1 (Setup)
    ↓
Phase 2 (Foundational) ← T003→T004 순서, T005/T007 병렬
    ↓
Phase 3 (US1) ────┐
Phase 4 (US2) ────┼─ 병렬 시작 가능 (Phase 2 완료 후)
Phase 5 (US3) ────┘
    ↓
Phase 6 (Polish)
```

### Within Each User Story

```
Interface (T008/T016)
    ↓
Tests (Red) — T009/T010/T011 병렬, T017/T018/T019 병렬
    ↓
Implementations (Green) — T012/T013/T014 병렬, T020/T021/T022 병렬
    ↓
Service 수정 (T015/T023)
```

### User Story Dependencies

- **US1 (P1)**: Phase 2 완료 후 시작 — 다른 US에 의존 없음
- **US2 (P2)**: Phase 2 완료 후 시작 — 다른 US에 의존 없음. `Stock`, `StockRepository`는 Phase 2에서 공유
- **US3 (P3)**: US2 완료 후 시작 권장 — 세 전략 구현 모두 필요

---

## Parallel Execution Examples

### Phase 2

```
[동시 실행 가능]
T005: Stock 엔티티 생성
T007: Order unique 제약 추가

[순서 필요]
T003 → T004 (Aspect가 어노테이션에 의존)
T005 → T006 (Repository가 Entity에 의존)
```

### Phase 3 (US1)

```
[T008 완료 후 동시 실행]
T009: OrderOptimisticLockTest 작성
T010: OrderPessimisticLockTest 작성
T011: OrderDistributedLockTest 작성

[테스트 완료 후 동시 실행]
T012: OrderOptimisticLockStrategy 구현
T013: OrderPessimisticLockStrategy 구현
T014: OrderDistributedLockStrategy 구현
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1: 의존성·설정 추가
2. Phase 2: Stock 엔티티, Order 제약, DistributedLock AOP
3. Phase 3: OrderLockStrategy 세 전략 + OrderService 연동
4. **STOP and VALIDATE**: `./gradlew test --tests "*.strategy.Order*LockTest"`
5. US1 검증 완료 후 US2 진행

### Incremental Delivery

1. Setup + Foundational → 인프라 준비 완료
2. US1 (주문 중복 방지) → Strategy 패턴 검증
3. US2 (재고 Overselling 방지) → 재고 동시성 검증
4. US3 (성능 비교) → 학습 목표 완성
5. Polish → 전체 통합 검증

---

## Notes

- [P] 태스크 = 다른 파일에 작업하며 미완료 태스크에 의존하지 않음
- TDD 순서: Interface 생성 → Test 작성(Red 확인) → 구현(Green 확인) → 리팩토링
- `./gradlew test` 각 단계마다 실행 필수
- 각 테스트 파일은 테스트 대상 파일과 동일한 서브패키지에 위치 (constitution 규칙 V)
- `InventoryLockPerformanceTest`는 세 전략 구현 모두 의존 — US2 완료 후 작성 권장
