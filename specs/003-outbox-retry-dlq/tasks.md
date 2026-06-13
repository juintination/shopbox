# Tasks: Outbox Retry & Dead Letter Queue

**Input**: Design documents from `/specs/003-outbox-retry-dlq/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/dead-letter-api.md, quickstart.md

**Organization**: US1(P1) — Outbox 재시도 전략 / US2(P2) — DLQ 재처리

---

## Phase 1: Setup

**Purpose**: 기존 설정 파일에 신규 설정 추가

- [ ] T001 `src/main/resources/application.yaml`에 `outbox.relay.max-retry: 5` 설정 추가

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: US1·US2 모두가 의존하는 공통 엔티티/Repository 생성. 이 단계 완료 전 어떤 User Story도 시작 불가.

**⚠️ CRITICAL**: T002·T003 병렬 실행 가능. T004는 T003 완료 후 실행.

- [ ] T002 [P] `src/main/kotlin/com/example/shopbox/outbox/entity/OutboxEvent.kt`에 `var retryCount: Int = 0` (
  `@Column(name = "retry_count", nullable = false)`) 필드 추가
- [ ] T003 [P] `src/main/kotlin/com/example/shopbox/outbox/entity/DeadLetterEvent.kt` 신규 생성 — `BaseEntity` 상속,
  `private constructor`, `create()` factory, `@SQLRestriction("deleted_at is null")`, `@SQLDelete`,
  `@Table(name = "dead_letter_events", indexes = [Index(name = "idx_dead_letter_events_deleted_at", columnList = "deleted_at")])`,
  필드: `id(@Tsid)`, `outboxEventId`, `aggregateType`, `aggregateId`, `eventType`, `payload`, `errorMessage`
- [ ] T004 `src/main/kotlin/com/example/shopbox/outbox/repository/DeadLetterEventRepository.kt` 신규 생성 —
  `JpaRepository<DeadLetterEvent, Long>` 상속 (T003 완료 후)

**Checkpoint**: Foundation ready — US1·US2 구현 시작 가능

---

## Phase 3: User Story 1 — Outbox 재시도 전략 (Priority: P1) 🎯 MVP

**Goal**: Kafka 발행 실패 시 `retry_count`를 추적하고, `max-retry` 초과 시 DLQ로 격리

**Independent Test**: `outbox_events`에 다양한 `retry_count` 상태 이벤트를 준비하고 Kafka 발행 성공/실패 시나리오를 실행하여 `retry_count` 변화와 DLQ 이동
여부를 `MessageRelayTest`(Mockk)로 독립 검증

### Tests for User Story 1 (TDD — 🔴 Red 먼저 확인)

- [ ] T005 [US1] `src/test/kotlin/com/example/shopbox/outbox/relay/MessageRelayTest.kt`에 다음 3개 시나리오 추가:
    1. Kafka 발행 실패 (`retry_count < max-retry`) → `retry_count` 1 증가, `processed_at` 미변경,
       `deadLetterEventRepository.save()` 미호출
    2. Kafka 발행 실패 (`retry_count >= max-retry`) → `deadLetterEventRepository.save()` 1회 호출 (error_message 포함),
       `outboxEventRepository.save()` 1회 호출 (`processed_at` 업데이트)
    3. 폴링 필터링 — `findByProcessedAtIsNullAndRetryCountLessThan(maxRetry)` 호출 검증

### Implementation for User Story 1

- [ ] T006 [US1] `src/main/kotlin/com/example/shopbox/outbox/repository/OutboxEventRepository.kt`에서 기존
  `findByProcessedAtIsNull()` 제거 후 `findByProcessedAtIsNullAndRetryCountLessThan(maxRetry: Int): List<OutboxEvent>` 추가 (
  T002 완료 후)
- [ ] T007 [US1] `src/main/kotlin/com/example/shopbox/outbox/relay/MessageRelay.kt` 변경 —
  `@Value("\${outbox.relay.max-retry:5}") private val maxRetry: Int` 주입, `relay()` 로직을 다음으로 교체:
  `findByProcessedAtIsNullAndRetryCountLessThan(maxRetry)` 조회 → 발행 성공 시 `processedAt` 업데이트 → 발행 실패 시 `retryCount++` 후
  `save()`, `retryCount >= maxRetry`이면 `DeadLetterEvent.create(...)` 저장 + `processedAt` 업데이트 (T005·T006·T004 완료 후)

**Checkpoint**: `MessageRelayTest` 전체 통과 확인. US1 독립 검증 완료.

---

## Phase 4: User Story 2 — Dead Letter Queue 재처리 (Priority: P2)

**Goal**: DLQ 이벤트 목록 조회 및 수동 재처리 API 제공

**Independent Test**: `dead_letter_events`에 이벤트를 준비하고 `GET /api/dead-letters`, `POST /api/dead-letters/{id}/retry` API를
호출하여 목록 조회, 재처리(outbox_events 재등록 + Soft delete), 404 처리를 `DeadLetterControllerTest`(Testcontainers)로 독립 검증

### Tests for User Story 2 (TDD — 🔴 Red 먼저 확인)

- [ ] T008 [P] [US2] `src/test/kotlin/com/example/shopbox/outbox/service/DeadLetterServiceTest.kt` 신규 작성 — Mockk 단위 테스트,
  시나리오: (1) `findAll()` → `deleted_at IS NULL` 이벤트 목록 반환, (2) `retry(id)` → `outbox_events` 재등록 + DLQ Soft delete, (3)
  `retry(없는 id)` → `BusinessException` 발생
- [ ] T009 [P] [US2] `src/test/kotlin/com/example/shopbox/outbox/controller/DeadLetterControllerTest.kt` 신규 작성 —
  Testcontainers 통합 테스트 (`@SpringBootTest`, `LockStrategyContainersInitializer` 또는 동일 initializer 활용), 시나리오: (1)
  `GET /api/dead-letters` → 200 + 미재처리 목록, (2) `POST /api/dead-letters/{id}/retry` → 200 + `outbox_events` 신규 레코드, DLQ
  Soft delete 확인, (3) `POST /api/dead-letters/999/retry` → 400

### Implementation for User Story 2

- [ ] T010 [P] [US2] `src/main/kotlin/com/example/shopbox/outbox/dto/response/DeadLetterResponse.kt` 신규 생성 — `id`,
  `outboxEventId`, `aggregateType`, `aggregateId`, `eventType`, `payload`, `errorMessage`, `createdAt` 필드,
  `companion object { fun from(event: DeadLetterEvent) }` factory
- [ ] T011 [US2] `src/main/kotlin/com/example/shopbox/outbox/service/DeadLetterService.kt` 신규 구현 —
  `findAll(): List<DeadLetterResponse>`, `retry(id: Long): DeadLetterResponse` (없는 id →
  `BusinessException("DLQ 이벤트를 찾을 수 없습니다: id=$id")`; 재처리 시 `OutboxEvent` 신규 저장 + DLQ `deletedAt = now()` + `save()`) (
  T008·T010 완료 후)
- [ ] T012 [US2] `src/main/kotlin/com/example/shopbox/outbox/controller/DeadLetterController.kt` 신규 구현 —
  `@RestController`, `@RequestMapping("/api/dead-letters")`, `GET /` →
  `ResponseEntity<ApiResponse<List<DeadLetterResponse>>>`, `POST /{id}/retry` →
  `ResponseEntity<ApiResponse<DeadLetterResponse>>` (T009·T011 완료 후)

**Checkpoint**: `DeadLetterServiceTest` + `DeadLetterControllerTest` 전체 통과 확인. US2 독립 검증 완료.

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: 누락 케이스 보완 및 검증

- [ ] T013 `src/test/kotlin/com/example/shopbox/outbox/controller/DeadLetterControllerTest.kt`에 이미 재처리된(deleted_at IS
  NOT NULL) DLQ 이벤트 재처리 시도 → 400 Bad Request 시나리오 추가 (이미 재처리된 이벤트의 Soft delete 처리 여부 확인)
- [ ] T014 `src/test/kotlin/com/example/shopbox/outbox/relay/MessageRelayTest.kt`에 `max-retry = 0` 설정 시 첫 번째 실패에서 즉시 DLQ
  이동 시나리오 추가

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 즉시 시작 가능
- **Foundational (Phase 2)**: Setup 완료 후 — **US1·US2 모두 Block**
- **US1 (Phase 3)**: Foundational 완료 후
- **US2 (Phase 4)**: Foundational 완료 후 (US1과 독립적으로 병렬 진행 가능)
- **Polish (Phase 5)**: US1·US2 모두 완료 후

### User Story Dependencies

- **US1 (P1)**: T002, T003, T004 완료 후 시작
- **US2 (P2)**: T002, T003, T004 완료 후 시작 (US1과 병렬 가능)

### Within Each User Story

- 테스트 → 구현 순서 엄수 (TDD, 🔴 Red 확인 필수)
- US1: T005(Red) → T006, T007(Green)
- US2: T008+T009+T010(Red, 병렬) → T011, T012(Green)

---

## Parallel Opportunities

```
Phase 2: T002 ║ T003 → T004
Phase 3: T005 → T006 ║ T007 (T006 먼저)
Phase 4: T008 ║ T009 ║ T010 → T011 → T012
```

---

## Implementation Strategy

### MVP (US1 Only)

1. Phase 1: T001
2. Phase 2: T002 → T003 → T004
3. Phase 3: T005 → T006 → T007
4. **STOP & VALIDATE**: `MessageRelayTest` 통과, retry_count 동작 확인

### Incremental

1. Setup + Foundational → T001~T004
2. US1 → T005~T007 (retry/DLQ 격리 완성)
3. US2 → T008~T012 (재처리 API 완성)
4. Polish → T013~T014

---

## Notes

- [P] = 다른 파일, 의존성 없음 → 병렬 실행 가능
- 🔴 Red 미확인 상태에서 구현 진입 금지 (Constitution 원칙)
- `OutboxEventRepository.findByProcessedAtIsNull()` 삭제 시 기존 `MessageRelayTest` 픽스처도 함께 업데이트 필요
- `DeadLetterControllerTest`의 컨테이너 초기화는 기존 `TestContainersInitializer` 또는 `LockStrategyContainersInitializer` 패턴을 그대로 따름
