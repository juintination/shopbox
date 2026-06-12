# Quickstart: Concurrency Control

**Feature**: `002-concurrency-control`
**Date**: 2026-06-08

---

## 전략 전환 방법

`src/main/resources/application.yml`의 설정만 바꾸면 코드 변경 없이 전략이 교체된다:

```yaml
order:
  lock-strategy: optimistic   # optimistic | pessimistic | distributed

inventory:
  lock-strategy: optimistic
```

---

## 동시성 테스트 실행

### 전체 테스트 실행

```bash
./gradlew test
```

### 특정 전략 동시성 테스트만 실행

```bash
# 낙관적 락 동시성 테스트
./gradlew test --tests "*.strategy.InventoryOptimisticLockTest"
./gradlew test --tests "*.strategy.OrderOptimisticLockTest"

# 비관적 락 동시성 테스트
./gradlew test --tests "*.strategy.InventoryPessimisticLockTest"
./gradlew test --tests "*.strategy.OrderPessimisticLockTest"

# 분산락 동시성 테스트
./gradlew test --tests "*.strategy.InventoryDistributedLockTest"
./gradlew test --tests "*.strategy.OrderDistributedLockTest"
```

### 성능 비교 테스트 실행

```bash
./gradlew test --tests "*.strategy.InventoryLockPerformanceTest"
```

---

## 동시성 테스트 시나리오 (US1: 주문 중복 방지)

**시나리오**: 동일 사용자가 같은 상품을 50개 스레드로 동시 주문

```
Thread 1  ─────────────────────────────────────────── createOrder(userId=1, productId=1)
Thread 2  ─────────────────────────────────────────── createOrder(userId=1, productId=1)
Thread 3  ─────────────────────────────────────────── createOrder(userId=1, productId=1)
...
Thread 50 ─────────────────────────────────────────── createOrder(userId=1, productId=1)

Result: successCount = 1, Order count in DB = 1
```

**관찰 포인트**:
- Optimistic: 1건 성공, 49건은 `ObjectOptimisticLockingFailureException` 또는 `DataIntegrityViolationException`
- Pessimistic: 1건 성공, 49건은 check-then-fail 또는 타임아웃
- Distributed: Redis 락으로 직렬화 → 1건 성공, 49건 락 획득 실패

---

## 동시성 테스트 시나리오 (US2: 재고 Overselling 방지)

**시나리오**: 재고 100개 상품에 200개 스레드 동시 차감

```
Stock: productId=1, quantity=100

Thread 1..200  ─── deductStock(productId=1, quantity=1)

Result: successCount = 100, Stock.quantity = 0
```

**관찰 포인트**:
- Optimistic: 성공 건수가 100이 아닐 수 있다 (충돌 재시도 실패 시). 하지만 `quantity >= 0`은 항상 유지됨.
  - 재시도 3회 내에 성공하지 못하면 실패로 처리됨.
- Pessimistic: 정확히 100건 성공. 락 대기로 순차 처리.
- Distributed: 정확히 100건 성공. Redis 락으로 순차 처리.

---

## 성능 비교 출력 예시 (US3)

`InventoryLockPerformanceTest` 실행 시 다음 형태의 측정 결과가 출력된다:

```
=== 락 전략 성능 비교 (재고=100, 동시 스레드=200) ===

전략             처리시간(ms)  성공건수  실패건수  TPS
낙관적 락         1234        87       113      141.0
비관적 락         3456        100      100      57.9
분산락            5678        100      100      35.2
```

**해석 가이드**:
- 낙관적 락은 충돌 재시도 비용으로 성공 건수가 100에 미달할 수 있다.
- 비관적 락은 락 대기로 처리 시간이 길지만 정확히 100건이 성공한다.
- 분산락은 Redis 네트워크 왕복 비용으로 가장 느리지만 정확하다.

---

## 인프라 준비 (Testcontainers)

동시성 테스트는 Testcontainers를 사용해 자동으로 MySQL과 Redis 컨테이너를 기동한다. 별도 인프라 설정 없이 `./gradlew test`만 실행하면 된다. Docker가 실행 중이어야 한다.

```
Docker required:
  - MySQL 8.x  (Testcontainers가 자동 기동)
  - Redis 7.x  (Testcontainers가 자동 기동)
```
