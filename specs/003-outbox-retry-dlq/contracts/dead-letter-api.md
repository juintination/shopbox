# API Contracts: Dead Letter Queue

## 공통 응답 포맷

모든 응답은 기존 `ApiResponse<T>` 래퍼를 사용한다.

```json
{
  "success": true,
  "data": { ... },
  "errors": null
}
```

```json
{
  "success": false,
  "data": null,
  "errors": ["에러 메시지"]
}
```

---

## GET /api/dead-letters

DLQ에 격리된 미재처리 이벤트 목록을 조회한다 (`deleted_at IS NULL`).

### Request

```
GET /api/dead-letters
Content-Type: application/json
```

파라미터 없음.

### Response 200 OK

```json
{
  "success": true,
  "data": [
    {
      "id": 123456789,
      "outboxEventId": 987654321,
      "aggregateType": "Order",
      "aggregateId": 100,
      "eventType": "OrderCreated",
      "payload": "{\"eventId\":\"...\",\"eventType\":\"OrderCreated\"}",
      "errorMessage": "org.apache.kafka.common.errors.TimeoutException: ...",
      "createdAt": "2026-06-13T10:00:00"
    }
  ],
  "errors": null
}
```

| 필드           | 타입   | 설명                        |
|----------------|--------|-----------------------------|
| `id`           | Long   | DLQ 이벤트 ID (TSID)        |
| `outboxEventId`| Long   | 원본 `outbox_events.id`     |
| `aggregateType`| String | ex. `"Order"`               |
| `aggregateId`  | Long   | 관련 엔티티 ID               |
| `eventType`    | String | ex. `"OrderCreated"`        |
| `payload`      | String | 원본 이벤트 JSON             |
| `errorMessage` | String | Kafka 발행 실패 원인         |
| `createdAt`    | String | DLQ 진입 시각 (ISO 8601)    |

---

## POST /api/dead-letters/{id}/retry

특정 DLQ 이벤트를 `outbox_events`에 재등록하고 Soft delete 처리한다.

### Request

```
POST /api/dead-letters/123456789/retry
```

Path parameter:

| 파라미터 | 타입 | 설명                |
|----------|------|---------------------|
| `id`     | Long | DLQ 이벤트 ID (TSID)|

Body 없음.

### Response 200 OK

```json
{
  "success": true,
  "data": {
    "id": 123456789,
    "outboxEventId": 987654321,
    "aggregateType": "Order",
    "aggregateId": 100,
    "eventType": "OrderCreated",
    "payload": "{...}",
    "errorMessage": "org.apache.kafka.common.errors.TimeoutException: ...",
    "createdAt": "2026-06-13T10:00:00"
  },
  "errors": null
}
```

재처리 등록된 DLQ 이벤트 정보를 반환한다.

### Response 400 Bad Request

존재하지 않는 id 요청 시:

```json
{
  "success": false,
  "data": null,
  "errors": ["DLQ 이벤트를 찾을 수 없습니다: id=123456789"]
}
```

---

## 에러 응답 매핑

| 상황                    | HTTP Status | `errors` 내용                              |
|-------------------------|-------------|---------------------------------------------|
| 존재하지 않는 DLQ id    | 400         | `"DLQ 이벤트를 찾을 수 없습니다: id={id}"` |
| 이미 재처리된 DLQ 이벤트| 400         | `"이미 재처리된 DLQ 이벤트입니다: id={id}"` |
| 서버 내부 오류           | 500         | `"서버 내부 오류가 발생했습니다"`           |
