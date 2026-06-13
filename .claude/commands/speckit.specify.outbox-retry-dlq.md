# Specify - Outbox Retry & Dead Letter Queue

다음 요구사항을 기반으로 프로젝트 스펙을 작성해줘.

## 프로젝트 개요

기존 Message Relay에서 Kafka 발행 실패 시 단순 재시도만 하던 구조를 개선한다.
재시도 횟수를 추적하고, 최대 재시도 횟수 초과 시 Dead Letter Queue(DLQ)로 이동시켜
실패한 이벤트를 안전하게 격리하고 추후 재처리할 수 있는 구조를 만든다.

## 도메인 개요

기존 `outbox_events` 테이블에 `retry_count` 컬럼을 추가하고,
최대 재시도 횟수는 `application.yml`에서 설정한다.
최대 재시도 횟수를 초과한 이벤트는 `dead_letter_events` 테이블로 이동한다.

## 변경 사항

### outbox_events 테이블 변경

기존 테이블에 다음 컬럼을 추가한다:

| 컬럼            | 타입  | 설명                 |
|---------------|-----|--------------------|
| `retry_count` | INT | 현재 재시도 횟수 (기본값: 0) |

### dead_letter_events 테이블 신규 생성

`BaseEntity`를 상속받아 Soft delete를 적용한다.
DLQ는 단순한 재처리 대기열이 아니라 **실패 이력 추적** 목적도 가진다.
Soft delete를 적용함으로써 재처리 완료 후에도 언제, 어떤 이벤트가,
왜 DLQ에 들어왔는지 이력을 보존할 수 있다:

- `created_at`: DLQ 진입 시각 (언제 들어왔는지)
- `error_message`: 실패 원인 (왜 들어왔는지)
- `deleted_at`: 재처리 완료 시각 (언제 재처리됐는지, null이면 미재처리)

| 컬럼                | 타입              | 설명                                  |
|-------------------|-----------------|-------------------------------------|
| `id`              | BIGINT UNSIGNED | TSID                                |
| `outbox_event_id` | BIGINT UNSIGNED | 원본 outbox_events ID                 |
| `aggregate_type`  | VARCHAR         | ex. "Order"                         |
| `aggregate_id`    | BIGINT UNSIGNED | 관련 엔티티 ID                           |
| `event_type`      | VARCHAR         | ex. "OrderCreated"                  |
| `payload`         | JSON            | 이벤트 데이터                             |
| `error_message`   | VARCHAR         | 실패 원인 (왜 들어왔는지)                     |
| `created_at`      | DATETIME        | DLQ 진입 시각 (BaseEntity)              |
| `updated_at`      | DATETIME        | 마지막 수정 시각 (BaseEntity)              |
| `deleted_at`      | DATETIME        | 재처리 완료 시각 (BaseEntity, Soft delete) |

### application.yml 변경

```yaml
outbox:
  relay:
    interval: 5000
    max-retry: 5  # 최대 재시도 횟수
```

## Phase 1: Outbox 재시도 전략

### 기능 요구사항

- Message Relay는 Kafka 발행 실패 시 `retry_count`를 1 증가시킨다
- `retry_count < max-retry`인 이벤트만 재시도 대상으로 조회한다
- `retry_count >= max-retry`인 이벤트는 `dead_letter_events`로 이동한다
- DLQ 이동 시 `outbox_events`에서는 삭제하지 않고 `processed_at`을 업데이트한다
  (이력 보존)

### 시나리오

**[정상 - 발행 성공]**

- Given: `retry_count = 0`인 미처리 이벤트가 있다
- When: Message Relay가 실행된다
- Then: Kafka에 발행된다
- Then: `processed_at`이 업데이트된다
- Then: `retry_count`는 변경되지 않는다

**[실패 - 발행 실패, 재시도 가능]**

- Given: `retry_count = 2`, `max-retry = 5`인 이벤트가 있다
- When: Kafka 발행에 실패한다
- Then: `retry_count`가 3으로 증가한다
- Then: `processed_at`은 업데이트되지 않는다
- Then: 다음 폴링 사이클에서 재시도 대상이 된다

**[실패 - 최대 재시도 초과]**

- Given: `retry_count = 5`, `max-retry = 5`인 이벤트가 있다
- When: Kafka 발행에 실패한다
- Then: `dead_letter_events`에 `error_message`와 함께 저장된다
- Then: `outbox_events`의 `processed_at`이 업데이트된다 (이력 보존)
- Then: 다음 폴링 사이클에서 재시도 대상에서 제외된다

## Phase 2: Dead Letter Queue

### 기능 요구사항

- `dead_letter_events`에 저장된 이벤트는 수동으로 재처리할 수 있어야 한다
- 재처리 시 `outbox_events`에 새로운 이벤트로 등록하고 `retry_count`를 0으로 초기화한다
- 재처리 등록 후 `dead_letter_events`는 Soft delete로 처리한다 (이력 보존)
- `dead_letter_events`는 `deleted_at`이 null인 것만 조회한다 (`@SQLRestriction` 적용)

### 시나리오

**[정상 - DLQ 이벤트 재처리]**

- Given: `dead_letter_events`에 이벤트가 있다
- When: 재처리 요청이 들어온다
- Then: `outbox_events`에 `retry_count = 0`으로 새로 등록된다
- Then: `dead_letter_events`의 `deleted_at`이 업데이트된다 (Soft delete)
- Then: 다음 폴링 사이클에서 재시도 대상이 된다

**[엣지 케이스 - DLQ 이벤트 없음]**

- Given: `dead_letter_events`에 이벤트가 없다
- When: 재처리 요청이 들어온다
- Then: 예외가 발생한다

## 전체 흐름

```
Message Relay 실행
      │
      ▼
outbox_events 조회 (processed_at = null AND retry_count < max-retry)
      │
      ▼
Kafka 발행 시도
      ├── 성공 → processed_at 업데이트
      └── 실패 → retry_count 증가
                    │
                    ├── retry_count < max-retry → 다음 폴링에서 재시도
                    └── retry_count >= max-retry → dead_letter_events 이동 (error_message 포함)
                                                        │
                                                        ▼
                                                  수동 재처리 API
                                                        │
                                                        ▼
                                                  outbox_events 재등록 (retry_count = 0)
                                                  dead_letter_events Soft delete
```

## API 엔드포인트

| 엔드포인트                           | 설명             |
|---------------------------------|----------------|
| `GET /dead-letters`             | DLQ 이벤트 목록 조회  |
| `POST /dead-letters/{id}/retry` | 특정 DLQ 이벤트 재처리 |
