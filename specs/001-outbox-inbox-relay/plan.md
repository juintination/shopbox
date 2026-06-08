# Implementation Plan: Transactional Outbox / Inbox / Message Relay

**Branch**: `001-outbox-inbox-relay` | **Date**: 2026-06-06 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-outbox-inbox-relay/spec.md`

## Summary

Implement the Transactional Outbox / Inbox / Message Relay pattern in a single-module
Spring Boot 3.x (Kotlin) application. Order creation atomically writes both the order
row and an `outbox_events` row in one DB transaction; a scheduled Message Relay polls
`outbox_events` and publishes to Kafka; downstream contexts (Payment, Inventory, Delivery)
consume via `@KafkaListener` and achieve idempotency through a PK-guarded `inbox_events`
table.

## Technical Context

**Language/Version**: Kotlin 2.2.21 (JVM 21)
**Primary Dependencies**: Spring Boot 3.5.14, Spring Data JPA, spring-kafka (BOM-managed 3.3.x), hypersistence-utils-hibernate-63 3.15.2 (TSID)
**Storage**: MySQL (localhost:33306/shopbox_db, `ddl-auto: update` for dev)
**Testing**: Kotest 5.9.1 (BehaviorSpec) + Mockk 1.13.17 + Testcontainers (BOM-managed, Kafka 4.2.0 KRaft image + MySQL)
**Target Platform**: JVM 21, single Docker-Compose local environment
**Project Type**: Web service (Spring Boot REST + Kafka consumer/producer)
**Performance Goals**: Correctness over throughput (learning project); relay latency ≤ configured poll interval (default 5 s)
**Constraints**: Message Relay must be structurally replaceable by CDC without code restructuring (Constitution §IV)
**Scale/Scope**: 4 Bounded Contexts (Order, Payment, Inventory, Delivery); single-module, package-enforced boundaries

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Pre-Phase-0 Gate

| # | Principle                        | Status | Notes |
|---|----------------------------------|--------|-------|
| I | Test-First (TDD)                 | ✅ PASS | All tasks follow Red→Green→Refactor with explicit `./gradlew test` |
| II | Test Scope                      | ✅ PASS | Only `{Domain}ServiceTest` (Mockk) + `{Domain}ControllerTest` (Testcontainers) |
| III | DDD Bounded Context Architecture | ✅ PASS | Order / Payment / Inventory / Delivery — Controller→Service→Repository |
| IV | Outbox/Inbox/Relay guarantees   | ✅ PASS | Atomic write, PK idempotency, relay decoupled for CDC swap |
| V | Package Structure               | ✅ PASS | Per-context `controller/service/repository/entity/dto/event/exception` |
| VI | ID Policy (TSID)                | ✅ PASS | `@Tsid` + `BIGINT UNSIGNED` on all entity PKs |
| Soft Delete | BaseEntity + `@SQLDelete` | ✅ PASS | Order/Payment/Inventory/Delivery; OutboxEvent+InboxEvent exempt (append-only) |
| Entity Naming | No `Entity` suffix         | ✅ PASS | `order/entity/Order.kt`, not `OrderEntity.kt` |

**Result**: All gates pass. Proceeding to Phase 0.

### Post-Phase-1 Re-check

| # | Principle        | Status | Notes |
|---|------------------|--------|-------|
| IV | Relay design    | ✅ PASS | `@Scheduled` relay bean is isolated; CDC replacement requires only swapping the scheduler trigger, not the publish/update logic |
| V | OutboxEvent placement | ✅ PASS | `outbox/` package in `common`-adjacent position; not inside any single bounded context |
| All others | — | ✅ PASS | No design changes introduced violations |

## Project Structure

### Documentation (this feature)

```text
specs/001-outbox-inbox-relay/
├── plan.md          # This file
├── research.md      # Phase 0 — dependency, scheduling, idempotency decisions
├── data-model.md    # Phase 1 — all entity definitions with field types
├── quickstart.md    # Phase 1 — local dev setup & test run guide
├── contracts/
│   └── kafka-events.md   # Phase 1 — Kafka event schemas (v1.0.0)
└── tasks.md         # Phase 2 output (/speckit-tasks command)
```

### Source Code

```text
src/main/kotlin/com/example/shopbox/
├── common/
│   ├── entity/
│   │   └── BaseEntity.kt
│   ├── exception/
│   │   └── BusinessException.kt
│   ├── event/
│   │   └── DomainEvent.kt
│   └── dto/
│       └── response/
│           └── ApiResponse.kt
├── order/
│   ├── controller/
│   │   └── OrderController.kt
│   ├── service/
│   │   └── OrderService.kt
│   ├── repository/
│   │   └── OrderRepository.kt
│   ├── entity/
│   │   └── Order.kt
│   ├── domain/
│   │   └── enums/
│   │       └── OrderStatus.kt
│   ├── dto/
│   │   ├── request/
│   │   │   └── CreateOrderRequest.kt
│   │   └── response/
│   │       └── OrderResponse.kt
│   ├── event/
│   │   └── OrderCreatedEvent.kt
│   └── exception/
│       └── OrderNotFoundException.kt
├── payment/
│   ├── controller/
│   │   └── PaymentController.kt
│   ├── service/
│   │   └── PaymentService.kt          # @KafkaListener on order-events
│   ├── repository/
│   │   └── PaymentRepository.kt
│   ├── entity/
│   │   └── Payment.kt
│   ├── domain/
│   │   └── enums/
│   │       └── PaymentStatus.kt
│   ├── dto/
│   │   └── response/
│   │       └── PaymentResponse.kt
│   └── event/
│       └── PaymentCompletedEvent.kt
├── inventory/
│   ├── service/
│   │   └── InventoryService.kt        # @KafkaListener on payment-events
│   ├── repository/
│   │   └── InventoryRepository.kt
│   ├── entity/
│   │   └── Inventory.kt
│   ├── domain/
│   │   └── enums/
│   │       └── InventoryStatus.kt
│   └── event/
│       └── StockReservedEvent.kt
├── delivery/
│   ├── service/
│   │   └── DeliveryService.kt         # @KafkaListener on inventory-events
│   ├── repository/
│   │   └── DeliveryRepository.kt
│   ├── entity/
│   │   └── Delivery.kt
│   ├── domain/
│   │   └── enums/
│   │       └── DeliveryStatus.kt
│   └── event/
│       └── DeliveryStartedEvent.kt
├── outbox/
│   ├── entity/
│   │   └── OutboxEvent.kt
│   ├── repository/
│   │   └── OutboxEventRepository.kt
│   └── relay/
│       └── MessageRelay.kt            # @Scheduled polling
└── inbox/
    ├── entity/
    │   └── InboxEvent.kt
    └── repository/
        └── InboxEventRepository.kt

src/test/kotlin/com/example/shopbox/
├── order/
│   ├── OrderServiceTest.kt            # Kotest BehaviorSpec + Mockk
│   └── OrderControllerTest.kt         # Kotest BehaviorSpec + Testcontainers
├── payment/
│   ├── PaymentServiceTest.kt
│   └── PaymentControllerTest.kt
├── inventory/
│   └── InventoryServiceTest.kt
└── delivery/
    └── DeliveryServiceTest.kt
```

**Structure Decision**: Single-module Spring Boot with package-enforced DDD boundaries.
`outbox/` and `inbox/` are top-level infrastructure packages shared across all contexts
(not nested inside a single bounded context). This layout is directly extractable into
separate modules without structural changes.

## Complexity Tracking

> No Constitution violations introduced. Table left empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| — | — | — |
