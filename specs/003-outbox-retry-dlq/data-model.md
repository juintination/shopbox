# Data Model: Outbox Retry & Dead Letter Queue

## 변경 엔티티

### OutboxEvent (기존 변경)

`retry_count` 컬럼 추가. `BaseEntity` 비상속 유지 (append-only, `@SQLRestriction` 불필요).

```
outbox_events
├── id              BIGINT UNSIGNED  PK, TSID
├── aggregate_type  VARCHAR(100)     NOT NULL
├── aggregate_id    BIGINT UNSIGNED  NOT NULL
├── event_type      VARCHAR(100)     NOT NULL
├── payload         JSON             NOT NULL
├── processed_at    DATETIME         NULL (null = 미처리)
├── created_at      DATETIME         NOT NULL
└── retry_count     INT              NOT NULL DEFAULT 0   ← NEW
```

**폴링 조건 변경**:
- Before: `processed_at IS NULL`
- After: `processed_at IS NULL AND retry_count < max-retry`

**상태 전이**:
```
[생성] retry_count=0, processed_at=null
   │
   ├── Kafka 성공 → processed_at=now(), retry_count 변경 없음
   │
   └── Kafka 실패 → retry_count++
                      │
                      ├── retry_count < max-retry → 다음 폴링에서 재시도
                      └── retry_count >= max-retry → dead_letter_events 이동
                                                      + processed_at=now() (폴링 제외)
```

---

## 신규 엔티티

### DeadLetterEvent (신규)

`BaseEntity` 상속 (Soft delete: `deleted_at`이 재처리 완료 시각을 의미).

```
dead_letter_events
├── id                BIGINT UNSIGNED  PK, TSID           ← BaseEntity 아님, 직접 선언
├── outbox_event_id   BIGINT UNSIGNED  NOT NULL           (원본 outbox_events.id, FK 미사용)
├── aggregate_type    VARCHAR(100)     NOT NULL
├── aggregate_id      BIGINT UNSIGNED  NOT NULL
├── event_type        VARCHAR(100)     NOT NULL
├── payload           JSON             NOT NULL
├── error_message     VARCHAR(500)     NOT NULL
├── created_at        DATETIME         NOT NULL           ← BaseEntity (DLQ 진입 시각)
├── updated_at        DATETIME         NOT NULL           ← BaseEntity
└── deleted_at        DATETIME         NULL               ← BaseEntity (재처리 완료 시각)
```

**Index**:
- `deleted_at` 단일 인덱스 (Soft delete 필터링, Constitution 원칙)

**Soft Delete 적용**:
- `@SQLRestriction("deleted_at is null")` → 목록 조회 시 자동 필터링
- `@SQLDelete(sql = "UPDATE dead_letter_events SET deleted_at = now() WHERE id = ?")` → 재처리 시 Soft delete

**상태 전이**:
```
[DLQ 진입] deleted_at=null, error_message=실패원인
   │
   └── 재처리 API 호출 → outbox_events 재등록 + deleted_at=now()
```

---

## 연관 관계

```
outbox_events ──(outbox_event_id, 논리 참조)──► dead_letter_events
```

- FK 제약 없음: `outbox_events`의 `processed_at`이 설정된 후에도 원본 레코드가 유지되므로 참조 무결성보다 이력 보존이 우선.
- 재처리 시 `outbox_events`에 **신규** 레코드가 등록됨 (기존 레코드 재활용 아님).

---

## application.yml 변경

```yaml
outbox:
  relay:
    interval: 5000
    max-retry: 5   ← NEW
```
