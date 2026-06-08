# Specify - Outbox, Inbox, Relay

다음 요구사항을 기반으로 프로젝트 스펙을 작성해줘.

## 프로젝트 개요

이커머스 도메인에서 Transactional Outbox Pattern, Inbox Pattern, Message Relay를 단계적으로 구현한다.
구현 순서는 다음과 같다:

- Phase 1: Outbox Pattern (주문 생성 → Outbox 저장)
- Phase 2: Inbox Pattern (중복 메시지 방지)
- Phase 3: Message Relay (Outbox → Kafka 발행)

## 도메인 개요

사용자가 상품을 주문하면 다음 흐름으로 처리된다:

1. 주문 생성 (Order)
2. 결제 처리 (Payment)
3. 재고 차감 (Inventory)
4. 배송 시작 (Delivery)

각 단계는 Kafka 이벤트로 연결되며 서로 직접 호출하지 않는다.

## Kafka Topic 전략

`aggregate_type`별로 topic을 분리한다:

| Topic              | 구독 서비스        |
|--------------------|---------------|
| `order-events`     | Payment 서비스   |
| `payment-events`   | Inventory 서비스 |
| `inventory-events` | Delivery 서비스  |

각 서비스는 자신과 관련된 topic만 구독한다.

## Phase 1: Outbox Pattern

### 기능 요구사항

- 사용자는 상품을 주문할 수 있다
- 주문 생성 시 `orders` 테이블과 `outbox_events` 테이블에 반드시 같은 트랜잭션으로 저장되어야 한다
- `outbox_events` 저장에 실패하면 주문도 저장되지 않아야 한다
- 저장된 `outbox_events`는 미처리(`processed_at = null`) 상태로 시작한다

### 시나리오

**[정상]**

- Given: DB가 정상 동작 중이다
- When: 사용자가 주문 생성을 요청한다
- Then: `orders`와 `outbox_events`가 동시에 저장된다
- Then: `outbox_events`의 `processed_at`은 null이다

**[실패 - Outbox 저장 실패]**

- Given: `outbox_events` 테이블에 오류가 발생한다
- When: 사용자가 주문 생성을 요청한다
- Then: 주문도 저장되지 않는다 (트랜잭션 롤백)

### outbox_events 테이블 구조

| 컬럼               | 타입              | 설명                 |
|------------------|-----------------|--------------------|
| `id`             | BIGINT UNSIGNED | TSID               |
| `aggregate_type` | VARCHAR         | ex. "Order"        |
| `aggregate_id`   | BIGINT UNSIGNED | 관련 엔티티 ID          |
| `event_type`     | VARCHAR         | ex. "OrderCreated" |
| `payload`        | JSON            | 이벤트 데이터            |
| `created_at`     | DATETIME        | 생성 시각              |
| `processed_at`   | DATETIME        | null이면 미처리         |

## Phase 2: Inbox Pattern

### 기능 요구사항

- Payment 서비스는 Kafka에서 `OrderCreated` 이벤트를 수신한다
- 동일한 `message_id`를 가진 메시지는 두 번 처리되지 않아야 한다
- 메시지 처리와 `inbox_events` 저장은 같은 트랜잭션에서 이루어져야 한다
- 처리 실패 시 `inbox_events`에 기록되지 않아야 한다 (재처리 가능)

### 시나리오

**[정상 - 최초 수신]**

- Given: `message_id = "msg-001"`이 inbox에 없다
- When: `OrderCreated` 이벤트가 수신된다
- Then: 결제 처리 비즈니스 로직이 실행된다
- Then: `inbox_events`에 `"msg-001"`이 기록된다

**[중복 - 동일 메시지 재수신]**

- Given: `message_id = "msg-001"`이 이미 inbox에 있다
- When: 동일한 `OrderCreated` 이벤트가 다시 수신된다
- Then: 결제 처리 비즈니스 로직이 실행되지 않는다
- Then: `inbox_events`에 중복 저장되지 않는다

**[실패 - 비즈니스 로직 처리 실패]**

- Given: `message_id = "msg-001"`이 inbox에 없다
- When: 이벤트를 수신했으나 결제 처리 중 오류가 발생한다
- Then: `inbox_events`에 기록되지 않는다
- Then: 이벤트는 재처리 대상이 된다

### inbox_events 테이블 구조

| 컬럼               | 타입       | 설명                  |
|------------------|----------|---------------------|
| `message_id`     | VARCHAR  | PK, Kafka 메시지 고유 ID |
| `source_service` | VARCHAR  | ex. "order-service" |
| `event_type`     | VARCHAR  | ex. "OrderCreated"  |
| `payload`        | JSON     | 수신한 메시지 데이터         |
| `received_at`    | DATETIME | 수신 시각               |
| `processed_at`   | DATETIME | 처리 완료 시각            |

## Phase 3: Message Relay

### 기능 요구사항

- Message Relay는 `outbox_events`에서 미처리 이벤트를 주기적으로 조회한다
- 조회한 이벤트를 Kafka에 발행한다
- 발행 성공 시 `outbox_events`의 `processed_at`을 업데이트한다
- 발행 실패 시 `processed_at`을 업데이트하지 않는다 (재시도 가능)
- `@Scheduled`로 폴링 주기를 `application.yml`에서 설정 가능하게 한다 (`outbox.relay.interval`, 기본값: 5000ms)

### 시나리오

**[정상 - 미처리 이벤트 발행]**

- Given: `outbox_events`에 미처리 이벤트 N건이 있다
- When: Message Relay가 실행된다
- Then: N건 모두 Kafka에 발행된다
- Then: N건 모두 `processed_at`이 업데이트된다

**[실패 - Kafka 발행 실패]**

- Given: `outbox_events`에 미처리 이벤트가 있다
- When: Kafka 발행 중 오류가 발생한다
- Then: `processed_at`이 업데이트되지 않는다
- Then: 다음 폴링 사이클에서 재시도된다

**[엣지 케이스 - 미처리 이벤트 없음]**

- Given: `outbox_events`에 미처리 이벤트가 없다
- When: Message Relay가 실행된다
- Then: 아무 동작도 하지 않는다

## 전체 이벤트 흐름

```
OrderCreated      → order-events     → Payment 서비스 수신  → PaymentCompleted
PaymentCompleted  → payment-events   → Inventory 서비스 수신 → StockReserved
StockReserved     → inventory-events → Delivery 서비스 수신  → DeliveryStarted
```

## 이벤트 스키마 (공통)

모든 이벤트는 다음 공통 필드를 포함한다:

| 필드           | 타입     | 설명               |
|--------------|--------|------------------|
| `eventId`    | String | 고유 식별자           |
| `eventType`  | String | 이벤트 종류           |
| `occurredAt` | String | 발생 시각 (ISO 8601) |
| `payload`    | Object | 이벤트별 데이터         |
