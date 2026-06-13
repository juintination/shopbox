# Quickstart: Outbox Retry & Dead Letter Queue

## 전제 조건

- MySQL, Kafka, Redis가 실행 중이어야 한다 (Testcontainers로 자동 실행 가능)
- `outbox.relay.max-retry=5` (기본값)

---

## 시나리오 1: Kafka 발행 성공 (정상 흐름)

```
1. 주문 생성 → outbox_events 저장 (retry_count=0, processed_at=null)
2. MessageRelay.relay() 실행
3. Kafka 발행 성공
4. outbox_events.processed_at = now() 업데이트
5. 다음 폴링에서 해당 이벤트 조회 안 됨 (processed_at IS NOT NULL)
```

**검증 포인트**: `outbox_events.processed_at IS NOT NULL` AND `retry_count = 0`

---

## 시나리오 2: Kafka 발행 실패 → 재시도 반복 (retry_count 증가)

```
1. 주문 생성 → outbox_events 저장 (retry_count=0)
2. [Kafka DOWN] relay() 실행 → 발행 실패 → retry_count=1
3. [Kafka DOWN] relay() 실행 → 발행 실패 → retry_count=2
4. [Kafka DOWN] relay() 실행 → 발행 실패 → retry_count=3
5. [Kafka DOWN] relay() 실행 → 발행 실패 → retry_count=4
6. max-retry=5이므로 아직 폴링 대상 (retry_count < 5)
```

**검증 포인트**: `outbox_events.retry_count = 4`, `processed_at IS NULL`

---

## 시나리오 3: 최대 재시도 초과 → DLQ 이동

```
1. 주문 생성 → outbox_events 저장 (retry_count=0)
2. relay()를 max-retry번 실패 반복 → retry_count = max-retry(5)
3. 다음 relay() 실행 시:
   - retry_count >= max-retry이므로 폴링 대상에서 제외됨
   - (이전 사이클에서) dead_letter_events에 error_message와 함께 저장됨
   - outbox_events.processed_at = now() (폴링 제외, 이력 보존)
4. 이후 relay()에서 해당 이벤트 조회 안 됨
```

**검증 포인트**:
- `dead_letter_events`에 레코드 존재 (deleted_at IS NULL)
- `outbox_events.processed_at IS NOT NULL`
- `outbox_events` 레코드 삭제되지 않음

---

## 시나리오 4: DLQ 이벤트 재처리

```
1. [시나리오 3 완료 상태] dead_letter_events에 이벤트 존재
2. GET /dead-letters → DLQ 목록에서 id 확인
3. POST /dead-letters/{id}/retry 호출
4. outbox_events에 retry_count=0으로 새 레코드 등록
5. dead_letter_events.deleted_at = now() (Soft delete)
6. [Kafka UP] 다음 relay() 실행 → Kafka 발행 성공
```

**검증 포인트**:
- 신규 `outbox_events` 레코드 (retry_count=0)
- `dead_letter_events.deleted_at IS NOT NULL`
- `GET /dead-letters` 응답에서 해당 이벤트 미포함

---

## 테스트 시나리오 (통합 테스트)

### MessageRelayTest (단위 테스트, Mockk)

| 시나리오 | 검증 |
|----------|------|
| 발행 성공 | `processed_at` 업데이트, `retry_count` 미변경 |
| 발행 실패 (retry_count < max-retry) | `retry_count` 증가, `processed_at` 미변경 |
| 발행 실패 (retry_count >= max-retry) | DLQ 저장, `outbox_events.processed_at` 업데이트 |
| 폴링 필터링 | `retry_count >= max-retry`인 이벤트 조회 제외 |

### DeadLetterControllerTest (통합 테스트, Testcontainers)

| 시나리오 | 검증 |
|----------|------|
| DLQ 목록 조회 | `deleted_at IS NULL`인 이벤트만 반환 |
| DLQ 재처리 | `outbox_events` 신규 등록, DLQ Soft delete |
| 존재하지 않는 id 재처리 | 400 Bad Request |
