# Shopbox

이커머스 도메인(주문 → 결제 → 재고 → 배송)을 활용하여 분산 시스템의 핵심 메시징 패턴을
직접 구현하고 학습하는 프로젝트

## 구현 패턴

| 브랜치                       | 패턴                                           | 핵심 학습 내용                   |
|---------------------------|----------------------------------------------|----------------------------|
| `001-outbox-inbox-relay`  | Transactional Outbox / Inbox / Message Relay | 원자적 이벤트 저장, 멱등 소비, 폴링 릴레이  |
| `002-concurrency-control` | Concurrency Control                          | 낙관적 락 / 비관적 락 / 분산 락 전략 비교 |
| `003-outbox-retry-dlq`    | Outbox Retry & Dead Letter Queue             | 재시도 횟수 추적, DLQ 격리 및 수동 재처리 |
| `004-saga-choreography`   | Choreography Saga Pattern                    | 이벤트 기반 분산 트랜잭션, 보상 트랜잭션    |

## 기술 스택

| 레이어                 | 기술                         |
|---------------------|----------------------------|
| Language            | Kotlin 2.2.21              |
| Framework           | Spring Boot 3.5.14         |
| Database            | MySQL                      |
| Message Broker      | Kafka (spring-kafka)       |
| ORM                 | Spring Data JPA            |
| Distributed Lock    | Redis (Redisson)           |
| ID Generation       | TSID (hypersistence-utils) |
| Test Framework      | Kotest (BehaviorSpec)      |
| Mocking             | Mockk                      |
| Test Fixture        | Fixture Monkey             |
| Integration Testing | Testcontainers             |

## 아키텍처

### DDD Bounded Context

패키지로 경계를 구분하는 단일 모듈 구조다. 각 Context는 독립 서비스로 분리 가능한 구조를 유지한다.

```
src/main/kotlin/com/example/shopbox/
├── order/          ← 주문 Bounded Context
├── payment/        ← 결제 Bounded Context
├── inventory/      ← 재고 Bounded Context
├── delivery/       ← 배송 Bounded Context
├── outbox/         ← Outbox / Relay / DLQ
├── inbox/          ← Inbox 멱등성
└── common/         ← 공통 인프라 (BaseEntity, DomainEvent, ...)
```

Context 간 통신은 Kafka 이벤트로만 하며, 직접 패키지 참조는 금지한다.

각 Context 내부는 레이어드 아키텍처를 따른다.

```
{context}/
├── controller/     ← 요청/응답 처리만
├── service/        ← 비즈니스 로직 + 트랜잭션 (Entity 반환 금지, DTO만 반환)
├── repository/     ← 데이터 접근 (Spring Data JPA)
├── entity/         ← JPA Entity (private constructor + create() 팩토리)
│   └── enums/
├── dto/
│   ├── request/
│   └── response/
└── event/          ← 도메인 이벤트
```

### Outbox / Inbox / Message Relay

```
[Service]
  주문 저장 + outbox_events 저장 (같은 트랜잭션)
       ↓
[MessageRelay] (5초 폴링)
  outbox_events 조회 → Kafka 발행 → processed_at 업데이트
  발행 실패 → retry_count 증가 → max-retry 초과 시 dead_letter_events 이동
       ↓
[Kafka Consumer]
  inbox_events.saveIfAbsent(messageId) → 중복이면 skip
  비즈니스 로직 실행 + 다음 outbox_events 저장
```

### Saga Choreography (정상 흐름)

```
POST /api/orders
  → OrderCreated (outbox)
    → [Relay] order-events 발행
      → PaymentService 소비 → PaymentCompleted (outbox)
        → [Relay] payment-events 발행
          → InventoryService 소비 → StockReserved (outbox)
            → [Relay] inventory-events 발행
              → DeliveryService 소비 → DeliveryStarted (outbox)
                → [Relay] delivery-events 발행
                  → OrderService 소비 → Order.status = CONFIRMED
```

### 보상 트랜잭션 흐름

```
결제 실패:    PaymentFailed          → Order CANCELLED
재고 부족:    StockReservationFailed → PaymentRefunded → Order CANCELLED
배송 실패:    DeliveryFailed         → StockRestored → PaymentRefunded → Order CANCELLED
```

### 동시성 제어 전략 (Strategy Pattern)

`inventory.lock-strategy` / `order.lock-strategy` 설정값으로 전략을 교체한다.

| 전략    | 설정값           | 메커니즘                     |
|-------|---------------|--------------------------|
| 낙관적 락 | `optimistic`  | `@Version` + 충돌 시 재시도    |
| 비관적 락 | `pessimistic` | `SELECT FOR UPDATE`      |
| 분산 락  | `distributed` | Redisson `tryLock` (AOP) |

#### 성능 비교

**재고 차감** (재고 100개 상품에 200 스레드가 동시에 1개씩 차감 요청)

| 전략    | 소요 시간   | 성공  | 실패  | TPS  | 비고                                     |
|-------|---------|-----|-----|------|----------------------------------------|
| 낙관적 락 | 3,271ms | 83  | 117 | 25.4 | 충돌 재시도 횟수 초과로 성공률 83% (Overselling 없음) |
| 비관적 락 | 1,562ms | 100 | 100 | 64.0 | 가장 빠름, 정확히 100건 성공                     |
| 분산 락  | 3,423ms | 100 | 100 | 29.2 | Redis 네트워크 왕복으로 가장 느림, 정확히 100건 성공     |

**주문 생성** (동일 userId/productId로 50 스레드가 동시에 주문 생성 요청)

| 전략    | 소요 시간 | 성공 | 실패 | TPS  | 비고                    |
|-------|-------|----|----|------|-----------------------|
| 낙관적 락 | 139ms | 1  | 49 | 7.2  | 충돌 시 예외 반환, 정확히 1건 성공 |
| 비관적 락 | 94ms  | 1  | 49 | 10.6 | 가장 빠름, 정확히 1건 성공      |
| 분산 락  | 384ms | 1  | 49 | 2.6  | Redis 오버헤드, 정확히 1건 성공 |

> 재고 차감에서 낙관적 락의 성공률이 83%에 그친 이유는 최대 재시도 횟수(5회)를 초과한 요청이 예외로 처리되기 때문이다.
> 비관적 락은 단일 인스턴스 환경에서 가장 빠르지만, 다중 인스턴스 환경에서는 DB 락만으로 동시성을 제어하기 어려워 분산 락을 함께 고려할 수 있다.

---

## 개발 방식

이 프로젝트는 [Spec Kit](https://github.com/github/spec-kit)으로 SDD(Specification-Driven Development)를 적용하여 개발했다.

### SDD란

코드를 먼저 작성하는 대신, 스펙 문서를 먼저 작성하고 이를 기반으로 계획 → 태스크 → 구현 순서로 진행하는 방식이다. 각 단계에서 산출물이 명확하고, 구현 전에 설계 의사결정이 문서화된다.

```
[사용자 요구사항]
      ↓  /speckit-specify
  spec.md          ← 사용자 시나리오, Given/When/Then 형식
      ↓  /speckit-plan
  plan.md          ← 기술 컨텍스트, Constitution Check, 설계 결정
  research.md      ← 기술 조사 및 선택 근거
  data-model.md    ← 엔티티 설계
  contracts/       ← API 계약
  quickstart.md    ← 통합 테스트 시나리오
      ↓  /speckit-tasks
  tasks.md         ← TDD 순서로 정렬된 실행 가능한 태스크 목록
      ↓  /speckit-implement
  [구현]
```

### Spec Kit 워크플로

각 피처 브랜치마다 동일한 흐름을 따랐다.

**1단계: 스펙 작성 (`/speckit-specify`)**

자연어로 요구사항을 설명하면 `spec.md`가 생성된다. 각 User Story는 Given/When/Then 시나리오로 표현되고 우선순위(P1~P3)가 부여된다.

**2단계: 구현 계획 (`/speckit-plan`)**

`spec.md`를 기반으로 `plan.md`가 생성된다. 핵심은 **Constitution Check**로, 구현 전과 설계 후 두 번 프로젝트 원칙 준수 여부를 게이트로 검증한다.

**3단계: 태스크 분해 (`/speckit-tasks`)**

`plan.md`와 `spec.md`를 기반으로 `tasks.md`가 생성된다. 모든 태스크는 TDD 순서(테스트 먼저 → 구현)로 정렬되고 병렬 실행 가능 여부가 `[P]` 마커로 표시된다.

```
- [X] T005 [P] [US1] PaymentServiceTest.kt 작성  ← 테스트 먼저
- [X] T010 [P] [US1] PaymentService.kt 구현      ← 구현 나중
```

**4단계: 구현 (`/speckit-implement`)**

`tasks.md`의 순서대로 TDD 사이클을 따른다.

### TDD 사이클 (Non-Negotiable)

모든 태스크는 이 순서를 반드시 따른다.

1. **🔴 Red** : 테스트 작성 → `./gradlew test` 실패 확인
2. **🟢 Green** : 최소 구현 → `./gradlew test` 통과 확인
3. **🔵 Refactor** : 코드 정리 → `./gradlew test` 재확인

### Constitution (프로젝트 헌법)

`.specify/memory/constitution.md`에 프로젝트 전체의 비타협 원칙을 문서화했다.

- **테스트 범위** : ServiceTest(Mockk) + ControllerTest(Testcontainers) 두 종류만 허용
- **패키지 경계** : Context 간 직접 참조 금지, Kafka 이벤트로만 소통
- **Entity 생성** : `private constructor` + `companion object { fun create() }` 팩토리 강제
- **ID 정책** : 모든 PK에 TSID 사용, `UUID.randomUUID()` 사용 금지
- **Outbox 원칙** : 비즈니스 데이터와 Outbox 이벤트는 반드시 같은 트랜잭션
- **Inbox 원칙** : 동일 `message_id` 절대 두 번 처리 금지

Constitution은 버전으로 관리되며(현재 v1.6.0), 각 피처의 `plan.md`에 Constitution Check 결과가 기록된다.

---

## 테스트 구조

```
test/
├── order/
│   ├── service/OrderServiceTest.kt         ← Mockk 단위 테스트
│   ├── controller/OrderControllerTest.kt   ← Testcontainers 통합 테스트
│   └── service/strategy/
│       ├── OrderOptimisticLockTest.kt
│       ├── OrderPessimisticLockTest.kt
│       ├── OrderDistributedLockTest.kt
│       └── OrderLockPerformanceTest.kt     ← 락 전략 성능 비교
├── payment/
├── inventory/
│   └── service/strategy/
│       └── InventoryLockPerformanceTest.kt
├── outbox/
│   ├── relay/MessageRelayTest.kt
│   ├── service/DeadLetterServiceTest.kt
│   └── controller/DeadLetterControllerTest.kt
└── support/containers/TestContainersInitializer.kt
```

통합 테스트는 MySQL + Kafka + Redis 컨테이너를 Testcontainers로 구동한다. Saga 통합 테스트는 Awaitility로 비동기 이벤트 체인의 최종 상태를 검증한다.

## 스펙 문서 구조

```
specs/
├── 001-outbox-inbox-relay/
│   ├── spec.md         ← 사용자 시나리오
│   ├── plan.md         ← 구현 계획 + Constitution Check
│   ├── research.md     ← 기술 조사
│   ├── data-model.md   ← 엔티티 설계
│   ├── contracts/      ← API 계약
│   ├── quickstart.md   ← 통합 시나리오
│   ├── tasks.md        ← 실행 태스크 목록
│   └── checklists/     ← 구현 체크리스트
├── 002-concurrency-control/
├── 003-outbox-retry-dlq/
└── 004-saga-choreography/
```
