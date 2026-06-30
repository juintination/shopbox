# Implementation Plan: Saga Pattern (Choreography)

**Branch**: `004-saga-choreography` | **Date**: 2026-06-15 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/004-saga-choreography/spec.md`

## Summary

기존 이커머스 Saga 정상 흐름(Order → Payment → Inventory → Delivery)에
보상 트랜잭션(결제 실패 → 주문 취소, 재고 부족 → 환불 → 취소, 배송 실패 → 재고 복구 → 환불 → 취소)을 추가한다.
신규 상태값 3개(CONFIRMED, REFUNDED, RESTORED), 보상 이벤트 클래스 6개,
보상 Kafka Listener를 기존 서비스에 추가하며 Inbox Pattern 멱등성은 기존 구조를 재사용한다.

## Technical Context

**Language/Version**: Kotlin 2.x  
**Primary Dependencies**: Spring Boot 3.x, Spring Data JPA, spring-kafka, Testcontainers  
**Storage**: MySQL (기존 `orders`, `payments`, `inventories`, `deliveries`, `outbox_events`, `inbox_events`)  
**Testing**: Kotest (BehaviorSpec) + Mockk (단위) + Testcontainers (통합)  
**Target Platform**: JVM / Linux server  
**Project Type**: Single-module Spring Boot web service  
**Performance Goals**: 기존 Relay 폴링 성능 유지 (보상 이벤트도 동일 Outbox Relay 사용)  
**Constraints**: 기존 Kafka 토픽 구조 유지 (토픽 추가 없음, 기존 4개 토픽에 보상 이벤트 혼재)  
**Scale/Scope**: 기존 단일 모듈 구조 내 각 Bounded Context 확장

## Constitution Check

### Pre-Phase 0 Gate

| 원칙 | 검토 | 결과 |
|------|------|------|
| TDD 적용 — 실패 테스트 없이 구현 금지 | 각 서비스 ServiceTest 먼저 작성 (보상 Listener 직접 호출), ControllerTest(Saga 통합)도 먼저 | PASS |
| 테스트 범위 — ServiceTest(Mockk) + ControllerTest(Testcontainers)만 허용 | 4개 ServiceTest + 기존 OrderControllerTest 확장 | PASS |
| DDD Bounded Context — 패키지 경계 준수 | 보상 로직 각 BC 서비스 내에 위치 (OrderService, PaymentService 등) | PASS |
| Service → DTO 반환 (Entity 반환 금지) | 보상 핸들러는 반환값 없음 (Listener) 또는 ResponseEntity 유지 | PASS |
| Entity 생성 — private constructor + create() | 신규 Entity 없음, 기존 Entity status 변경만 | PASS |
| Outbox Pattern — 보상 이벤트도 같은 트랜잭션에 outbox 저장 | 모든 보상 핸들러: 상태 업데이트 + outbox 저장 동일 트랜잭션 | PASS |
| Inbox Pattern — 동일 messageId 중복 처리 금지 | 기존 `inboxEventRepository.saveIfAbsent()` 패턴 재사용 | PASS |
| TSID — 모든 이벤트 ID | 신규 이벤트 클래스에 `TSID.fast().toString()` 사용 | PASS |
| Coding Convention — 파라미터 줄바꿈, trailing comma | 준수 | PASS |

### Post-Phase 1 Gate

| 원칙 | 검토 | 결과 |
|------|------|------|
| 구현 전 기존 코드 읽기 | PaymentService, InventoryService, DeliveryService, OrderService, DomainEvent, Entity 전부 확인 | PASS |
| Entity status 업데이트 누락 수정 | processOrderCreated/processPaymentCompleted/processStockReserved에 status 업데이트 추가 결정 | PASS |
| 동일 토픽 여러 이벤트 처리 | payment-events/inventory-events를 단일 @KafkaListener로 수신 + eventType 분기 결정 | PASS |
| CompensationEvent 분리 | 기존 DomainEvent 불변 유지, CompensationEvent 인터페이스 신규 생성 | PASS |

## Project Structure

### Documentation (this feature)

```text
specs/004-saga-choreography/
├── plan.md              ← This file
├── spec.md              ← Feature specification
├── research.md          ← Phase 0 decisions
├── data-model.md        ← Entity/Event 설계
├── quickstart.md        ← Integration scenarios
├── contracts/
│   └── kafka-events.md  ← Kafka 이벤트 스키마
├── checklists/
│   └── requirements.md  ← Spec quality checklist
└── tasks.md             ← Phase 2 output (/speckit-tasks command)
```

### Source Code (변경/신규 파일)

```text
src/main/kotlin/com/example/shopbox/
├── common/
│   └── event/
│       └── CompensationEvent.kt              ← 신규 (보상 이벤트 인터페이스)
│
├── order/
│   ├── entity/enums/
│   │   └── OrderStatus.kt                    ← 변경 (CONFIRMED 추가)
│   ├── event/
│   │   └── OrderCancelledEvent.kt            ← 신규
│   └── service/
│       └── OrderService.kt                   ← 변경 (Saga Listener 추가: payment-events, delivery-events)
│
├── payment/
│   ├── entity/enums/
│   │   └── PaymentStatus.kt                  ← 변경 (REFUNDED 추가)
│   ├── event/
│   │   ├── PaymentFailedEvent.kt             ← 신규
│   │   └── PaymentRefundedEvent.kt           ← 신규
│   └── service/
│       └── PaymentService.kt                 ← 변경 (status 업데이트 + 실패 처리 + inventory-events Listener)
│
├── inventory/
│   ├── entity/enums/
│   │   └── InventoryStatus.kt                ← 변경 (RESTORED 추가)
│   ├── event/
│   │   ├── StockReservationFailedEvent.kt    ← 신규
│   │   └── StockRestoredEvent.kt             ← 신규
│   └── service/
│       └── InventoryService.kt               ← 변경 (status 업데이트 + 실패 처리 + delivery-events Listener)
│
└── delivery/
    ├── event/
    │   └── DeliveryFailedEvent.kt            ← 신규
    └── service/
        └── DeliveryService.kt                ← 변경 (status 업데이트 + 실패 처리)

src/test/kotlin/com/example/shopbox/
├── order/
│   ├── service/
│   │   └── OrderServiceTest.kt              ← 신규 (보상 Listener 단위 테스트)
│   └── controller/
│       └── OrderControllerTest.kt           ← 변경 (Saga 정상 흐름 통합 테스트 추가)
├── payment/
│   └── service/
│       └── PaymentServiceTest.kt            ← 신규 (결제 실패 + 환불 단위 테스트)
├── inventory/
│   └── service/
│       └── InventoryServiceTest.kt          ← 신규 (재고 부족 + 복구 단위 테스트)
└── delivery/
    └── service/
        └── DeliveryServiceTest.kt           ← 신규 (배송 실패 단위 테스트)
```

## Complexity Tracking

Constitution 위반 없음. 추가 항목 없음.
