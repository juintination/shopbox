# Feature Specification: Concurrency Control

**Feature Branch**: `002-concurrency-control`
**Created**: 2026-06-08
**Status**: Draft

## User Scenarios & Testing

### User Story 1 - 주문 생성 동시성 제어 (Priority: P1)

동일한 사용자가 같은 상품을 동시에 여러 번 주문하더라도 중복 주문이 생성되지 않는다.
낙관적 락, 비관적 락, 분산락 세 가지 전략 모두 이 보장을 만족한다.

**Why this priority**: 중복 주문은 비즈니스 데이터 무결성의 핵심 요건이다. 재고 차감보다 단순한 구조이므로 Strategy 패턴과 각 락 전략의 기본 동작을 먼저 검증하기 위해 P1으로 설정한다.

**Independent Test**: 동일 사용자/상품 조합으로 N개 스레드가 동시에 주문을 생성할 때, 성공 건수가 정확히 1건인지 확인함으로써 독립적으로 검증 가능하다.

**Acceptance Scenarios**:

1. **Given** 동일한 사용자가 같은 상품을 동시에 N번 주문 요청할 때, **When** `OptimisticLockStrategy`로 처리하면, **Then** 1건만 성공하고 나머지는 실패하며 중복 주문이 생성되지 않는다.
2. **Given** 동일한 사용자가 같은 상품을 동시에 N번 주문 요청할 때, **When** `PessimisticLockStrategy`로 처리하면, **Then** 1건만 성공하고 나머지는 실패하며 중복 주문이 생성되지 않는다.
3. **Given** 동일한 사용자가 같은 상품을 동시에 N번 주문 요청할 때, **When** `DistributedLockStrategy`로 처리하면, **Then** 1건만 성공하고 나머지는 실패하며 중복 주문이 생성되지 않는다.

---

### User Story 2 - 재고 차감 동시성 제어 (Priority: P2)

재고가 100개인 상품에 200명이 동시에 주문하더라도 정확히 100건만 성공하고 Overselling이 발생하지 않는다.
낙관적 락, 비관적 락, 분산락 세 가지 전략 모두 이 보장을 만족한다.

**Why this priority**: Overselling은 실제 재고보다 더 많이 팔리는 심각한 문제이나, 주문 생성 동시성 제어(P1)에서 Strategy 패턴과 락 구조를 먼저 확립한 후에 검증한다.

**Independent Test**: 재고 100개 상품에 200개 스레드가 동시에 차감을 요청할 때, 성공 건수가 정확히 100건이고 재고가 0 미만으로 내려가지 않음을 확인함으로써 독립적으로 검증 가능하다.

**Acceptance Scenarios**:

1. **Given** 재고가 100개인 상품에 200명이 동시에 주문할 때, **When** `OptimisticLockStrategy`로 처리하면, **Then** 정확히 100건만 성공하고 재고가 0 미만으로 내려가지 않는다.
2. **Given** 재고가 100개인 상품에 200명이 동시에 주문할 때, **When** `PessimisticLockStrategy`로 처리하면, **Then** 정확히 100건만 성공하고 재고가 0 미만으로 내려가지 않는다.
3. **Given** 재고가 100개인 상품에 200명이 동시에 주문할 때, **When** `DistributedLockStrategy`로 처리하면, **Then** 정확히 100건만 성공하고 재고가 0 미만으로 내려가지 않는다.

---

### User Story 3 - 락 전략 성능 비교 (Priority: P3)

동일한 동시성 시나리오에서 세 가지 락 전략의 처리 시간, 성공/실패 건수, TPS를 측정하고 비교한다.
Kafka 오버헤드를 제거하기 위해 Service를 직접 호출하는 방식으로 측정한다.

**Why this priority**: 정확성 검증(P1, P2) 이후 성능 특성을 비교한다. 성능 비교는 학습 목적이며 특정 임곗값 충족이 필수 조건은 아니다.

**Independent Test**: 동일한 부하 조건에서 각 전략을 순차적으로 실행하고 처리 시간과 TPS를 측정함으로써 독립적으로 검증 가능하다.

**Acceptance Scenarios**:

1. **Given** 재고가 100개인 상품에 200명이 동시 주문할 때, **When** 세 전략을 각각 실행하면, **Then** 각 전략의 처리 시간(ms), 성공 건수, 실패 건수, TPS가 출력된다.
2. **Given** 동일한 성능 비교 조건에서, **When** 낙관적 락을 사용하면, **Then** 충돌 재시도로 인해 실패 건수가 발생하며 처리 시간이 측정된다.
3. **Given** 동일한 성능 비교 조건에서, **When** 비관적 락을 사용하면, **Then** 락 대기로 인해 처리 시간이 측정된다.
4. **Given** 동일한 성능 비교 조건에서, **When** 분산락을 사용하면, **Then** 네트워크 왕복(Redis)을 포함한 처리 시간이 측정된다.

---

### Edge Cases

- 낙관적 락 충돌 재시도 횟수 초과 시 `ObjectOptimisticLockingFailureException`이 호출자에게 전파된다.
- 비관적 락 획득 대기 중 타임아웃 발생 시 예외가 전파된다.
- Redisson 락 획득 실패(타임아웃) 시 예외가 전파된다.
- Redis 장애 시 분산락 전략은 예외를 발생시켜 요청을 거부한다.
- `application.yml`의 전략 설정이 없거나 잘못된 값이면 애플리케이션 기동 시 오류가 발생한다.

## Requirements

### Functional Requirements

**Phase 1 — Strategy 패턴 기반 주문 동시성 제어**

- **FR-001**: `OrderLockStrategy` 인터페이스를 정의하고 낙관적 락, 비관적 락, 분산락 구현체를 제공해야 한다.
- **FR-002**: `OrderService`는 단일 서비스로 유지하며, 주입된 `OrderLockStrategy`에 따라 동시성 제어 방식이 결정되어야 한다.
- **FR-003**: 낙관적 락은 `@Version`으로 충돌을 감지하고 재시도 로직을 포함해야 한다.
- **FR-004**: 비관적 락은 `@Lock(LockModeType.PESSIMISTIC_WRITE)`로 `SELECT FOR UPDATE`를 실행해야 한다.
- **FR-005**: 분산락은 Redisson으로 구현하고 AOP 방식으로 적용해야 한다.
- **FR-006**: 운영 환경에서 사용할 전략은 `application.yml`의 `order.lock-strategy` 설정으로 결정되어야 한다.

**Phase 2 — Strategy 패턴 기반 재고 차감 동시성 제어**

- **FR-007**: `InventoryLockStrategy` 인터페이스를 정의하고 낙관적 락, 비관적 락, 분산락 구현체를 제공해야 한다.
- **FR-008**: `InventoryService`는 단일 서비스로 유지하며, 주입된 `InventoryLockStrategy`에 따라 동시성 제어 방식이 결정되어야 한다.
- **FR-009**: 재고 차감 시 재고가 0 미만이 되는 요청은 거부되어야 한다.
- **FR-010**: Kafka Consumer는 기존 흐름을 유지하며 내부에서 `InventoryLockStrategy`를 사용해야 한다.
- **FR-011**: 운영 환경에서 사용할 전략은 `application.yml`의 `inventory.lock-strategy` 설정으로 결정되어야 한다.

**Phase 3 — 성능 비교 테스트**

- **FR-012**: 성능 비교 테스트는 Kafka 없이 Service를 직접 호출하는 방식으로 수행되어야 한다.
- **FR-013**: 처리 시간(ms), 성공 건수, 실패 건수, TPS를 측정하고 출력해야 한다.
- **FR-014**: 동일한 부하 조건에서 세 전략을 순차적으로 실행하여 비교해야 한다.

### Key Entities

- **Stock**: 재고 엔티티. `productId`, `quantity` 포함. 낙관적 락을 위한 `@Version` 필드 추가. `BaseEntity` 상속, Soft Delete 적용.
- **Order**: 기존 주문 엔티티. 중복 주문 방지를 위한 `(userId, productId)` 복합 unique 제약 추가.

### 전략 구성

| 전략 | 구현 방식 | 충돌 처리 |
|------|----------|----------|
| 낙관적 락 | JPA `@Version` | `ObjectOptimisticLockingFailureException` + 재시도 |
| 비관적 락 | JPA `@Lock(PESSIMISTIC_WRITE)` | 락 획득까지 대기 |
| 분산락 | Redisson AOP | 락 획득 실패 시 예외 |

### 패키지 구조

```
inventory/service/
  ├── InventoryService.kt
  └── strategy/
        ├── InventoryLockStrategy.kt
        ├── OptimisticLockStrategy.kt
        ├── PessimisticLockStrategy.kt
        └── DistributedLockStrategy.kt

order/service/
  ├── OrderService.kt
  └── strategy/
        ├── OrderLockStrategy.kt
        ├── OptimisticLockStrategy.kt
        ├── PessimisticLockStrategy.kt
        └── DistributedLockStrategy.kt
```

### 의존성

```kotlin
implementation("org.redisson:redisson-spring-boot-starter")
testImplementation("org.testcontainers:redis")
```

## Success Criteria

- **SC-001**: 동일 사용자/상품 조합으로 N번 동시 주문 시 세 전략 모두 정확히 1건만 성공한다 (중복 주문 0건).
- **SC-002**: 재고 100개 상품에 200명 동시 주문 시 세 전략 모두 정확히 100건만 성공하고 재고는 0 이상을 유지한다.
- **SC-003**: `application.yml` 설정 변경만으로 락 전략을 교체할 수 있다 (코드 변경 불필요).
- **SC-004**: 성능 비교 테스트에서 세 전략의 처리 시간, TPS, 성공/실패 건수가 수치로 출력된다.
- **SC-005**: 기존 Kafka Consumer 흐름(Inbox Pattern)이 동시성 제어 추가 후에도 정상 동작한다.

## Assumptions

- 분산락(Redisson)은 단일 Redis 노드로 구현한다 (RedLock 알고리즘 미적용).
- 낙관적 락의 재시도 횟수와 간격은 설정 가능하되 기본값을 제공한다.
- 성능 비교 테스트는 Testcontainers(MySQL + Redis)를 사용한 통합 테스트로 구현한다.
- 주문 생성 동시성 제어는 기존 Outbox 패턴과 결합하여 동작한다.
- 재고(Stock) 엔티티는 이 스펙에서 새로 추가한다. 기존 `Inventory` 엔티티(Kafka 소비용)와 별개로 관리한다.
- 인증/인가 및 주문-재고 연동의 전체 비즈니스 흐름은 이 스펙의 범위 밖이다.
