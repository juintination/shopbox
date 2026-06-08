# Specify - Concurrency Control

다음 요구사항을 기반으로 프로젝트 스펙을 작성해줘.

## 프로젝트 개요

이커머스 도메인에서 동시성 문제가 발생할 수 있는 두 가지 시나리오에 대해
낙관적 락, 비관적 락, 분산락(Redisson) 세 가지 전략을 Strategy 패턴으로 구현하고 성능을 비교한다.
성능 비교는 Kafka 오버헤드 없이 락 전략 자체의 성능을 순수하게 측정하기 위해
Service를 직접 호출하는 방식으로 진행한다.

## 동시성 제어가 필요한 시나리오

### 시나리오 1: 주문 생성 동시성 (중복 주문 방지)

- 동일한 사용자가 동시에 같은 상품을 여러 번 주문하는 경우
- 중복 주문이 발생하지 않아야 한다

### 시나리오 2: 재고 차감 동시성 (Overselling 방지)

- 재고가 100개인 상품에 200명이 동시에 주문하는 경우
- 실제 재고보다 더 많이 팔리는 Overselling이 발생하지 않아야 한다

## 서비스 구조

Strategy 패턴으로 구현한다. Service는 하나이고 락 전략만 교체 가능하다:

```
inventory/
  └── service/
        ├── InventoryService.kt                  ← 단일 Service
        └── strategy/
              ├── InventoryLockStrategy.kt        ← 인터페이스
              ├── OptimisticLockStrategy.kt
              ├── PessimisticLockStrategy.kt
              └── DistributedLockStrategy.kt

order/
  └── service/
        ├── OrderService.kt                      ← 단일 Service
        └── strategy/
              ├── OrderLockStrategy.kt            ← 인터페이스
              ├── OptimisticLockStrategy.kt
              ├── PessimisticLockStrategy.kt
              └── DistributedLockStrategy.kt
```

## Kafka Consumer 연동

기존 Kafka Consumer 흐름은 그대로 유지하되 내부에서 Strategy를 적용한다:

```
Kafka → InventoryConsumer → InventoryService (전략 주입)
                                ├── OptimisticLockStrategy
                                ├── PessimisticLockStrategy
                                └── DistributedLockStrategy
```

- 운영 환경에서 사용할 전략은 `application.yml`에서 설정한다:

```yaml
inventory:
  lock-strategy: optimistic  # optimistic | pessimistic | distributed
```

## 구현 전략

### 전략 1: 낙관적 락 (Optimistic Lock)

- JPA `@Version` 어노테이션으로 구현
- 충돌 감지 시 `ObjectOptimisticLockingFailureException` 발생
- 재시도 로직 포함

### 전략 2: 비관적 락 (Pessimistic Lock)

- JPA `@Lock(LockModeType.PESSIMISTIC_WRITE)`로 구현
- `SELECT FOR UPDATE` 쿼리 실행
- 락 획득까지 대기

### 전략 3: 분산락 (Distributed Lock)

- Redisson으로 구현
- AOP 방식으로 적용
- 락 획득 실패 시 예외 발생

## 시나리오

### [주문 생성 동시성]

**[정상 - 낙관적 락]**

- Given: 동일한 사용자가 같은 상품을 동시에 N번 주문 요청한다
- When: `OptimisticLockStrategy`로 처리한다
- Then: 1건만 성공하고 나머지는 실패한다
- Then: 중복 주문이 생성되지 않는다

**[정상 - 비관적 락]**

- Given: 동일한 사용자가 같은 상품을 동시에 N번 주문 요청한다
- When: `PessimisticLockStrategy`로 처리한다
- Then: 1건만 성공하고 나머지는 실패한다
- Then: 중복 주문이 생성되지 않는다

**[정상 - 분산락]**

- Given: 동일한 사용자가 같은 상품을 동시에 N번 주문 요청한다
- When: `DistributedLockStrategy`로 처리한다
- Then: 1건만 성공하고 나머지는 실패한다
- Then: 중복 주문이 생성되지 않는다

### [재고 차감 동시성]

**[정상 - 낙관적 락]**

- Given: 재고가 100개인 상품에 200명이 동시에 주문한다
- When: `OptimisticLockStrategy`로 처리한다
- Then: 정확히 100건만 성공한다
- Then: 재고가 0 미만으로 내려가지 않는다

**[정상 - 비관적 락]**

- Given: 재고가 100개인 상품에 200명이 동시에 주문한다
- When: `PessimisticLockStrategy`로 처리한다
- Then: 정확히 100건만 성공한다
- Then: 재고가 0 미만으로 내려가지 않는다

**[정상 - 분산락]**

- Given: 재고가 100개인 상품에 200명이 동시에 주문한다
- When: `DistributedLockStrategy`로 처리한다
- Then: 정확히 100건만 성공한다
- Then: 재고가 0 미만으로 내려가지 않는다

## 성능 비교 테스트

Kafka 오버헤드 없이 락 전략 자체의 성능을 비교하기 위해 Service를 직접 호출한다.
Kotest BehaviorSpec + Testcontainers (MySQL + Redis)로 동시 요청을 발생시켜 성능을 측정한다.

측정 항목:

| 항목    | 설명                       |
|-------|--------------------------|
| 처리 시간 | 전체 요청 처리 완료까지 소요 시간 (ms) |
| 성공 건수 | 정상 처리된 요청 수              |
| 실패 건수 | 락 충돌/획득 실패로 처리되지 못한 요청 수 |
| 처리량   | 초당 처리 건수 (TPS)           |

```kotlin
given("재고가 100개인 상품에 200명이 동시 주문할 때") {
    When("낙관적 락을 사용하면") {
        // InventoryService에 OptimisticLockStrategy 직접 주입
        // 200개 스레드로 동시 호출
        // 처리 완료까지 시간 측정
        then("처리 시간: Xms, 성공: 100건, 실패: 100건") { }
    }
    When("비관적 락을 사용하면") {
        // InventoryService에 PessimisticLockStrategy 직접 주입
        then("처리 시간: Xms, 성공: 100건, 실패: 100건") { }
    }
    When("분산락을 사용하면") {
        // InventoryService에 DistributedLockStrategy 직접 주입
        then("처리 시간: Xms, 성공: 100건, 실패: 100건") { }
    }
}
```

## 의존성 추가 (build.gradle.kts)

```kotlin
implementation("org.redisson:redisson-spring-boot-starter")
testImplementation("org.testcontainers:redis")
```
