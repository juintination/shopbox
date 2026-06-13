# Feature Specification: Outbox Retry & Dead Letter Queue

**Feature Branch**: `003-outbox-retry-dlq`
**Created**: 2026-06-13
**Status**: Draft

## Overview

기존 Message Relay는 Kafka 발행 실패 시 단순히 다음 폴링 사이클에서 재시도하는 구조다.
재시도 횟수 추적이 없기 때문에 영구 실패 이벤트가 폴링 대상으로 남아 계속 처리 시도된다.
이 기능은 재시도 횟수를 추적하고 최대 재시도 횟수 초과 시 Dead Letter Queue(DLQ)로 격리하여
실패 이벤트를 안전하게 보존하고 수동으로 재처리할 수 있는 구조를 제공한다.

## User Scenarios & Testing

### User Story 1 — Outbox 재시도 전략 (Priority: P1)

Message Relay는 Kafka 발행 실패 시 `retry_count`를 증가시키고,
최대 재시도 횟수를 초과한 이벤트는 DLQ로 이동시켜 폴링 대상에서 제외한다.

**Why this priority**: 재시도 추적이 없으면 DLQ 자체가 의미 없다.
`retry_count` 관리와 DLQ 이동 로직이 Relay의 핵심이므로 먼저 구현해야 한다.

**Independent Test**: `outbox_events`에 다양한 `retry_count` 상태의 이벤트를 준비하고
Kafka 발행 성공/실패 시나리오를 각각 실행하여 `retry_count` 변화 및 DLQ 이동 여부를
독립적으로 검증할 수 있다.

**Acceptance Scenarios**:

1. **[성공]** **Given** `retry_count = 0`인 미처리 이벤트가 있다, **When** Message Relay가 실행된다, **Then** Kafka에 발행된다, **Then** `processed_at`이 업데이트된다, **Then** `retry_count`는 변경되지 않는다.

2. **[실패 — 재시도 가능]** **Given** `retry_count = 2`, `max-retry = 5`인 이벤트가 있다, **When** Kafka 발행에 실패한다, **Then** `retry_count`가 3으로 증가한다, **Then** `processed_at`은 업데이트되지 않는다, **Then** 다음 폴링 사이클에서 재시도 대상이 된다.

3. **[실패 — 최대 재시도 초과]** **Given** `retry_count = 5`, `max-retry = 5`인 이벤트가 있다, **When** Kafka 발행에 실패한다, **Then** `dead_letter_events`에 `error_message`와 함께 저장된다, **Then** `outbox_events`의 `processed_at`이 업데이트된다 (이력 보존), **Then** 다음 폴링 사이클에서 재시도 대상에서 제외된다.

4. **[조회 필터링]** **Given** `retry_count < max-retry`인 이벤트와 `retry_count >= max-retry`인 이벤트가 섞여 있다, **When** Message Relay가 조회를 실행한다, **Then** `retry_count < max-retry`인 이벤트만 조회 대상이 된다.

---

### User Story 2 — Dead Letter Queue 재처리 (Priority: P2)

운영자가 DLQ에 격리된 이벤트를 조회하고 수동으로 재처리를 요청할 수 있다.
재처리 시 이벤트는 `outbox_events`에 `retry_count = 0`으로 새로 등록되어
다음 폴링 사이클에서 정상 처리된다.

**Why this priority**: DLQ에 이벤트를 격리만 하고 재처리 수단이 없으면 의미가 없다.
US1 이후 자연스러운 다음 단계.

**Independent Test**: `dead_letter_events`에 이벤트를 준비하고 재처리 API를 호출하여
`outbox_events` 신규 등록, `dead_letter_events` Soft delete,
다음 폴링 사이클에서 발행 시도 여부를 독립적으로 검증할 수 있다.

**Acceptance Scenarios**:

1. **[정상 — 재처리]** **Given** `dead_letter_events`에 이벤트가 있다, **When** 재처리 요청이 들어온다, **Then** `outbox_events`에 `retry_count = 0`으로 새로 등록된다, **Then** `dead_letter_events`의 `deleted_at`이 업데이트된다 (Soft delete), **Then** 다음 폴링 사이클에서 재시도 대상이 된다.

2. **[엣지 케이스 — 이벤트 없음]** **Given** 해당 id의 `dead_letter_events`가 존재하지 않는다, **When** 재처리 요청이 들어온다, **Then** 예외가 발생한다.

3. **[목록 조회]** **Given** `dead_letter_events`에 미재처리(`deleted_at IS NULL`) 이벤트가 있다, **When** DLQ 목록 조회 요청이 들어온다, **Then** `deleted_at IS NULL`인 이벤트만 반환된다.

---

### Edge Cases

- `max-retry = 0`으로 설정하면 첫 번째 실패 즉시 DLQ로 이동한다.
- DLQ로 이동한 이벤트의 `outbox_events` 레코드는 `processed_at`이 설정되어 폴링 대상에서 제외되지만 삭제되지는 않아 이력이 보존된다.
- 재처리 후 Kafka가 여전히 다운 상태면 새로 등록된 이벤트가 다시 `retry_count`를 소진하고 DLQ로 재진입할 수 있다.
- Relay 실행 중 DLQ 이동 자체가 실패하면(DB 오류 등) 해당 이벤트는 `retry_count`만 증가하고 다음 사이클에서 재시도된다.

## Requirements

### Functional Requirements

**Phase 1 — Outbox 재시도 전략**

- **FR-001**: `outbox_events` 테이블에 `retry_count` INT 컬럼(기본값 0)을 추가해야 한다.
- **FR-002**: Message Relay는 `processed_at IS NULL AND retry_count < max-retry`인 이벤트만 조회해야 한다.
- **FR-003**: Kafka 발행 성공 시 `processed_at`을 현재 시각으로 업데이트하고 `retry_count`는 변경하지 않아야 한다.
- **FR-004**: Kafka 발행 실패 시 `retry_count`를 1 증가시키고 `processed_at`은 변경하지 않아야 한다.
- **FR-005**: `retry_count >= max-retry`인 이벤트가 발행 실패하면 `dead_letter_events`로 이동해야 한다.
- **FR-006**: DLQ 이동 시 `outbox_events`의 `processed_at`을 업데이트하여 이후 폴링에서 제외해야 한다 (레코드 삭제 금지).
- **FR-007**: `max-retry`는 `outbox.relay.max-retry`로 외부 설정 가능해야 한다.

**Phase 2 — Dead Letter Queue**

- **FR-008**: `dead_letter_events` 테이블을 신규 생성해야 한다 (`BaseEntity` 상속, Soft delete 적용).
- **FR-009**: `GET /api/dead-letters` API는 `deleted_at IS NULL`인 DLQ 이벤트 목록을 반환해야 한다.
- **FR-010**: `POST /api/dead-letters/{id}/retry` API는 해당 DLQ 이벤트를 `outbox_events`에 `retry_count = 0`으로 재등록해야 한다.
- **FR-011**: 재처리 등록 후 `dead_letter_events`는 Soft delete(현재 시각으로 `deleted_at` 업데이트)해야 한다.
- **FR-012**: 존재하지 않는 id로 재처리 요청 시 예외를 반환해야 한다.

### Key Entities

- **OutboxEvent** (기존 변경): `retry_count` 컬럼 추가. `retry_count < max-retry`인 조건이 폴링 쿼리에 추가됨.
- **DeadLetterEvent** (신규): `BaseEntity` 상속, Soft delete 적용. DLQ 격리 및 재처리 이력 추적.
  - `outbox_event_id`, `aggregate_type`, `aggregate_id`, `event_type`, `payload`, `error_message`

### API Endpoints

| Method | Endpoint                      | 설명              |
|--------|-------------------------------|-----------------|
| GET    | `/api/dead-letters`               | DLQ 이벤트 목록 조회  |
| POST   | `/api/dead-letters/{id}/retry`    | 특정 DLQ 이벤트 재처리 |

### Configuration

```yaml
outbox:
  relay:
    interval: 5000   # 폴링 주기 (ms)
    max-retry: 5     # 최대 재시도 횟수
```

### 전체 흐름

```
Message Relay 실행
      │
      ▼
outbox_events 조회
(processed_at IS NULL AND retry_count < max-retry)
      │
      ▼
Kafka 발행 시도
      ├── 성공 → processed_at 업데이트
      └── 실패 → retry_count 증가
                    │
                    ├── retry_count < max-retry → 다음 폴링에서 재시도
                    └── retry_count >= max-retry → dead_letter_events 이동
                                                  + outbox_events.processed_at 업데이트
                                                        │
                                                        ▼
                                                  수동 재처리 API
                                                        │
                                                        ▼
                                                  outbox_events 재등록 (retry_count = 0)
                                                  dead_letter_events Soft delete
```

## Success Criteria

- **SC-001**: Kafka가 영구 다운 상태에서 Relay를 `max-retry + 1`회 실행하면 해당 이벤트가 `dead_letter_events`로 이동하고 이후 폴링 대상에서 제외된다.
- **SC-002**: DLQ 재처리 API 호출 후 `outbox_events`에 `retry_count = 0`으로 새 레코드가 생성되고 Kafka가 복구되면 자동 발행된다.
- **SC-003**: DLQ 이동 후 원본 `outbox_events` 레코드가 삭제되지 않고 이력이 보존된다.
- **SC-004**: `deleted_at`이 설정된 `dead_letter_events` 레코드는 목록 조회 API에서 반환되지 않는다.
- **SC-005**: `max-retry` 값을 애플리케이션 재시작 없이 설정 변경 후 반영할 수 있다.

## Assumptions

- 기존 `outbox_events` 테이블에 `retry_count` 컬럼을 마이그레이션으로 추가한다.
- DLQ 재처리는 운영자 수동 호출로만 실행되며 자동 재처리 스케줄러는 이 스펙의 범위 밖이다.
- `dead_letter_events`의 `outbox_event_id`는 참조 무결성을 강제하지 않는다 (원본 레코드 이력 보존이 목적이며 FK 제약은 불필요).
- 인증/인가는 이 스펙의 범위 밖이다.
