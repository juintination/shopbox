# Feature Specification: Transactional Outbox / Inbox / Message Relay

**Feature Branch**: `001-outbox-inbox-relay`
**Created**: 2026-06-06
**Status**: Draft

## User Scenarios & Testing

### User Story 1 - 원자적 주문 + Outbox 저장 (Priority: P1)

사용자가 상품을 주문하면 주문 데이터와 Outbox 이벤트가 반드시 같은 트랜잭션에 저장된다.
Outbox 저장에 실패하면 주문도 저장되지 않아 데이터 불일치가 발생하지 않는다.

**Why this priority**: 데이터 정합성의 출발점. 이 패턴 없이는 Inbox/Relay 패턴을 구현할 이유가 없다. MVP의 핵심.

**Independent Test**: 주문 생성 API를 호출했을 때 `orders`와 `outbox_events` 테이블에 동시에 레코드가 생성되는지, Outbox 저장 실패 시 주문도 롤백되는지 확인함으로써 독립적으로 검증 가능하다.

**Acceptance Scenarios**:

1. **Given** DB가 정상 동작 중일 때, **When** 사용자가 주문 생성을 요청하면, **Then** `orders`와 `outbox_events`가 동시에 저장되고 `outbox_events.processed_at`은 null이다.
2. **Given** `outbox_events` 테이블에 오류가 발생하는 상황일 때, **When** 사용자가 주문 생성을 요청하면, **Then** 주문도 저장되지 않는다 (트랜잭션 롤백).

---

### User Story 2 - 멱등 Inbox 처리 (Priority: P2)

Payment 서비스가 Kafka에서 `OrderCreated` 이벤트를 수신할 때, 동일한 메시지가 재전송되더라도 결제 처리는 정확히 한 번만 실행된다.

**Why this priority**: Outbox가 at-least-once 전송을 보장하므로 수신 측에서 중복을 막지 않으면 결제가 중복 실행될 수 있다. Outbox 다음 우선순위.

**Independent Test**: 동일한 `message_id`를 가진 이벤트를 두 번 소비했을 때 비즈니스 로직이 한 번만 실행되고 `inbox_events`에 중복 레코드가 생기지 않음을 확인함으로써 독립적으로 검증 가능하다.

**Acceptance Scenarios**:

1. **Given** `message_id = "msg-001"`이 inbox에 없을 때, **When** `OrderCreated` 이벤트가 수신되면, **Then** 결제 처리 비즈니스 로직이 실행되고 `inbox_events`에 `"msg-001"`이 기록된다.
2. **Given** `message_id = "msg-001"`이 이미 inbox에 있을 때, **When** 동일한 `OrderCreated` 이벤트가 다시 수신되면, **Then** 결제 처리 비즈니스 로직이 실행되지 않고 `inbox_events`에 중복 저장되지 않는다.
3. **Given** `message_id = "msg-001"`이 inbox에 없을 때, **When** 이벤트를 수신했으나 결제 처리 중 오류가 발생하면, **Then** `inbox_events`에 기록되지 않아 재처리 대상이 된다.

---

### User Story 3 - 주기적 Message Relay (Priority: P3)

Message Relay가 주기적으로 `outbox_events`를 폴링하여 미처리 이벤트를 Kafka에 발행하고, 발행 성공 시 처리 완료로 표시한다. Kafka 장애 시에는 다음 폴링 사이클에서 자동 재시도된다.

**Why this priority**: Outbox 이벤트를 실제로 Kafka에 전달하는 역할이지만, Outbox/Inbox 패턴 자체의 검증 이후에 구현해야 전체 흐름을 단계적으로 확인할 수 있다.

**Independent Test**: 미처리 Outbox 이벤트 N건이 있을 때 Relay 실행 후 Kafka 토픽에 N건이 발행되고 `processed_at`이 업데이트되는지, Kafka 장애 시 `processed_at`이 그대로인지 확인함으로써 독립적으로 검증 가능하다.

**Acceptance Scenarios**:

1. **Given** `outbox_events`에 미처리 이벤트 N건이 있을 때, **When** Message Relay가 실행되면, **Then** N건 모두 Kafka에 발행되고 `processed_at`이 업데이트된다.
2. **Given** `outbox_events`에 미처리 이벤트가 있을 때, **When** Kafka 발행 중 오류가 발생하면, **Then** `processed_at`이 업데이트되지 않아 다음 폴링 사이클에서 재시도된다.
3. **Given** `outbox_events`에 미처리 이벤트가 없을 때, **When** Message Relay가 실행되면, **Then** 아무 동작도 하지 않는다.

---

### Edge Cases

- `outbox_events` 저장 직후 애플리케이션이 비정상 종료되면 Relay가 재기동 후 해당 이벤트를 재처리한다.
- Relay 실행 중 동일 이벤트가 두 번 발행되더라도 Inbox의 멱등성 처리로 중복 비즈니스 실행이 방지된다.
- `inbox_events` PK(`message_id`) 중복 삽입 시 DB 레벨에서 제약 위반이 발생해 트랜잭션이 롤백된다.
- 미처리 이벤트 건수가 많을 때 Relay 한 사이클에서 전부 처리한다 (배치 조회).

## Requirements

### Functional Requirements

**Phase 1 — Outbox Pattern**

- **FR-001**: 사용자는 상품 주문을 생성할 수 있어야 한다.
- **FR-002**: 주문 생성 시 `orders`와 `outbox_events`는 반드시 같은 트랜잭션에 저장되어야 한다.
- **FR-003**: `outbox_events` 저장이 실패하면 주문도 저장되지 않아야 한다.
- **FR-004**: 저장된 `outbox_events`의 `processed_at`은 최초 null이어야 한다.

**Phase 2 — Inbox Pattern**

- **FR-005**: Payment 서비스는 Kafka `order-events` 토픽에서 `OrderCreated` 이벤트를 수신해야 한다.
- **FR-006**: 동일한 `message_id`를 가진 메시지는 두 번 처리되지 않아야 한다.
- **FR-007**: 비즈니스 로직 처리와 `inbox_events` 저장은 같은 트랜잭션에서 이루어져야 한다.
- **FR-008**: 비즈니스 로직 처리 실패 시 `inbox_events`에 기록되지 않아야 한다.

**Phase 3 — Message Relay**

- **FR-009**: Message Relay는 `outbox_events`에서 미처리(`processed_at IS NULL`) 이벤트를 주기적으로 조회해야 한다.
- **FR-010**: 조회한 이벤트를 `aggregate_type` 기반 Kafka 토픽에 발행해야 한다.
- **FR-011**: Kafka 발행 성공 시 `processed_at`을 현재 시각으로 업데이트해야 한다.
- **FR-012**: Kafka 발행 실패 시 `processed_at`을 업데이트하지 않아야 한다.
- **FR-013**: 폴링 주기는 `outbox.relay.interval` (기본값: 5000ms)으로 외부 설정 가능해야 한다.

### Key Entities

- **OutboxEvent**: 비즈니스 이벤트의 발행 보장용 중간 저장소. `aggregate_type`, `aggregate_id`, `event_type`, `payload`, `processed_at` 포함. `BaseEntity` 상속 제외 (append-only).
- **InboxEvent**: 중복 메시지 방지용 처리 이력. PK가 `message_id`(VARCHAR)이며 DB 레벨에서 중복 삽입을 차단. `BaseEntity` 상속 제외 (append-only).
- **Order**: 주문 도메인 엔티티. `BaseEntity` 상속, Soft Delete 적용.
- **Payment**: 결제 도메인 엔티티. `BaseEntity` 상속, Soft Delete 적용.

### Kafka Topic 전략

| Topic              | 발행 주체       | 구독 서비스        |
|--------------------|------------|---------------|
| `order-events`     | Message Relay | Payment 서비스   |
| `payment-events`   | Message Relay | Inventory 서비스 |
| `inventory-events` | Message Relay | Delivery 서비스  |

### 이벤트 스키마 (공통 필드)

모든 이벤트의 `payload`는 다음 공통 필드를 포함한다:

| 필드           | 타입     | 설명               |
|--------------|--------|------------------|
| `eventId`    | String | 고유 식별자           |
| `eventType`  | String | 이벤트 종류           |
| `occurredAt` | String | 발생 시각 (ISO 8601) |
| `payload`    | Object | 이벤트별 데이터         |

## Success Criteria

- **SC-001**: 주문 생성 요청 시 `orders`와 `outbox_events`가 항상 동시에 저장되거나 둘 다 저장되지 않는다 (원자성 100%).
- **SC-002**: 동일 `message_id`를 가진 이벤트가 재전송되어도 비즈니스 로직은 정확히 1회만 실행된다 (중복 처리율 0%).
- **SC-003**: Kafka 장애 후 복구 시 미처리 Outbox 이벤트가 자동으로 재발행된다 (수동 개입 불필요).
- **SC-004**: Message Relay 폴링 주기를 애플리케이션 재시작 없이 설정 변경으로 조정할 수 있다.
- **SC-005**: 전체 이벤트 흐름(OrderCreated → PaymentCompleted → StockReserved → DeliveryStarted)이 Testcontainers 통합 테스트로 검증 가능하다.

## Assumptions

- 단일 모듈 Spring Boot 애플리케이션으로 모든 Bounded Context(Order, Payment, Inventory, Delivery)를 패키지로 구분하여 구현한다.
- Message Relay는 폴링 방식으로 구현하며, CDC(Change Data Capture) 방식은 이 스펙에서 다루지 않는다.
- Inventory, Delivery 서비스는 Payment와 동일한 Inbox 패턴을 적용하나 상세 비즈니스 로직은 최소화한다.
- 인증/인가는 이 스펙의 범위 밖이다.
- 주문 생성 시 재고 확인은 이 스펙의 범위 밖이다 (패턴 학습에 집중).
