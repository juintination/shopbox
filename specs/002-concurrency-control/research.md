# Research: Concurrency Control

**Feature**: `002-concurrency-control`
**Date**: 2026-06-08

---

## Decision 1: Optimistic Lock Retry Strategy

**Decision**: Manual retry loop — 최대 3회, 50ms 지연, `@Transactional` 경계 외부에서 실행

**Rationale**: spring-retry 의존성 추가 없이 구현 가능. 3회 초과 시 `ObjectOptimisticLockingFailureException`을 상위로 전파.

**Critical Constraint**: 재시도 루프는 반드시 `@Transactional` 경계 밖에 위치해야 한다. 각 재시도는 독립적인 새 트랜잭션이어야 하므로, 재시도 호출자(`createOrder` / `deductStock`)는 `@Transactional` 없이 전략 클래스의 `@Transactional` 메서드를 반복 호출하는 방식으로 설계한다.

```kotlin
// 전략 클래스 내부
fun createOrderWithRetry(userId: Long, productId: Long, quantity: Int): Order {
    repeat(MAX_RETRY) { attempt ->
        try {
            return createOrderInTransaction(userId, productId, quantity) // @Transactional
        } catch (e: ObjectOptimisticLockingFailureException) {
            if (attempt == MAX_RETRY - 1) throw e
            Thread.sleep(RETRY_DELAY_MS)
        }
    }
    error("unreachable")
}

companion object {
    private const val MAX_RETRY = 3
    private const val RETRY_DELAY_MS = 50L
}
```

**Alternatives Considered**:
- `@Retryable` (spring-retry): rejected — 불필요한 의존성, 현재 프로젝트 범위 초과

---

## Decision 2: Pessimistic Lock Timeout

**Decision**: `@QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))` — 3000ms 락 대기 타임아웃

**Rationale**: 무한 대기 방지. 3초는 정상 트랜잭션 시간보다 충분히 길면서 테스트에서 타임아웃 시나리오를 검증하기 적합하다.

**Repository Pattern**:
```kotlin
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
@Query("SELECT s FROM Stock s WHERE s.productId = :productId")
fun findByProductIdForUpdate(
    @Param("productId") productId: Long,
): Stock?
```

**Alternatives Considered**:
- 타임아웃 없음: rejected — 테스트 환경에서 무한 대기 위험, 데드락 감지 불가

---

## Decision 3: Distributed Lock — Redisson AOP

**Decision**: Custom `@DistributedLock` 어노테이션 + `@Aspect` in `common/lock/`; SpEL 기반 동적 키

**Rationale**: 비즈니스 로직에서 락 관심사를 완전히 분리한다. 두 컨텍스트(Order, Inventory)에서 단일 Aspect를 공유한다.

**Critical Ordering — `@Transactional`보다 Aspect가 먼저 실행되어야 한다**:
- `@Order(Ordered.LOWEST_PRECEDENCE - 1)` 를 Aspect에 적용
- 순서: `락 획득` → `트랜잭션 시작` → `비즈니스 로직` → `트랜잭션 커밋` → `락 해제`
- 반대 순서(커밋 후 락 해제 전 다른 스레드 진입)를 방지하기 위해 락이 트랜잭션을 포함해야 한다

**Lock Parameters**:
- Key format: SpEL expression — `"'lock:stock:' + #productId"`, `"'lock:order:' + #userId + ':' + #productId"`
- Wait time: 5s (락 획득 대기 최대)
- Lease time: 10s (트랜잭션 타임아웃보다 충분히 길게)

**Annotation Design**:
```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class DistributedLock(
    val key: String,         // SpEL expression
    val waitTime: Long = 5000L,
    val leaseTime: Long = 10000L,
    val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
)
```

**Alternatives Considered**:
- 각 전략 클래스에 `redissonClient` 직접 주입: rejected — 중복 락 획득/해제 코드, AOP 학습 목적 미달성
- `@Transactional`과 동일한 Order: rejected — 커밋 이전 락 해제 시 다른 스레드가 커밋 전 데이터 읽기 가능

---

## Decision 4: Strategy Bean Selection

**Decision**: `@ConditionalOnProperty` 적용 — 각 전략 클래스에 선언, `application.yml`로 단일 빈 활성화

**Rationale**: Spring 네이티브 방식으로 별도 Factory 코드가 필요 없다. 잘못된 설정 값은 기동 시 `NoSuchBeanDefinitionException`으로 즉시 발견된다.

**Configuration**:
```yaml
# application.yml
order:
  lock-strategy: optimistic   # optimistic | pessimistic | distributed

inventory:
  lock-strategy: optimistic
```

**Bean Declaration**:
```kotlin
@Component
@ConditionalOnProperty(name = ["order.lock-strategy"], havingValue = "optimistic")
class OrderOptimisticLockStrategy(...) : OrderLockStrategy

@Component
@ConditionalOnProperty(name = ["order.lock-strategy"], havingValue = "pessimistic")
class OrderPessimisticLockStrategy(...) : OrderLockStrategy

@Component
@ConditionalOnProperty(name = ["order.lock-strategy"], havingValue = "distributed")
class OrderDistributedLockStrategy(...) : OrderLockStrategy
```

**Alternatives Considered**:
- `Map<String, OrderLockStrategy>` 기반 Factory: rejected — 설정 오류가 기동 후 런타임에서만 발견됨
- `@Qualifier`: rejected — 전략 교체 시 서비스 코드 수정 필요 → SC-003 위반

---

## Decision 5: Concurrent Test Infrastructure

**Decision**: `CountDownLatch` + `Executors.newFixedThreadPool` in Kotest `BehaviorSpec` + Testcontainers (MySQL + Redis)

**Rationale**: JVM 표준 동시성 프리미티브로 실제 병렬 요청을 재현한다. Testcontainers가 실제 MySQL/Redis 환경을 제공한다.

**Standard Test Pattern**:
```kotlin
val threadCount = 200
val startLatch = CountDownLatch(1)
val doneLatch = CountDownLatch(threadCount)
val successCount = AtomicInteger(0)
val executor = Executors.newFixedThreadPool(threadCount)

repeat(threadCount) {
    executor.submit {
        startLatch.await()
        try {
            strategy.deductStock(productId, 1)
            successCount.incrementAndGet()
        } catch (_: Exception) {
        } finally {
            doneLatch.countDown()
        }
    }
}
startLatch.countDown()
doneLatch.await(30, TimeUnit.SECONDS)
executor.shutdown()

successCount.get() shouldBe 100
stockRepository.findByProductId(productId)!!.quantity shouldBe 0
```

**Test Class Naming**: `{Domain}LockConcurrencyTest` — constitution 이탈 사유: `plan.md` Complexity Tracking 참조

**Alternatives Considered**:
- Kotest coroutines (`coroutineScope + launch`): rejected — 가상 스레드로 스케줄링되어 실제 병렬 실행 보장 없음

---

## Decision 6: Order Deduplication Mechanism

**Decision**: `Order` 테이블에 `(userId, productId)` 복합 unique 제약 추가; 각 전략이 이를 다른 방식으로 보장

**Rationale**: DB 레벨 unique 제약이 최후 방어선으로 동작한다. 전략별 동작:

| 전략 | 중복 방지 메커니즘 |
|------|----------------|
| OptimisticLock | check-then-insert; `DataIntegrityViolationException` 발생 시 실패 처리 |
| PessimisticLock | `SELECT ... FOR UPDATE`로 존재 여부 확인 후 삽입; unique 제약이 후방어 |
| DistributedLock | Redis 락으로 직렬화 후 check-then-insert |

**Alternatives Considered**:
- `OrderDedupKey` 전용 엔티티 추가: rejected — 별도 테이블 불필요, 기존 Order 엔티티로 충분

---

## Decision 7: New Dependencies

**Additions to `build.gradle.kts`**:

```kotlin
implementation("org.redisson:redisson-spring-boot-starter:3.27.2")
testImplementation("org.testcontainers:redis:1.20.4")
```

**Notes**:
- `redisson-spring-boot-starter`는 `redissonClient` 빈을 자동 구성한다. `application.yml`의 `spring.data.redis.*` 설정을 읽는다.
- `testcontainers:redis`는 `RedisContainer`를 제공한다. 이미 포함된 `spring-boot-testcontainers`와 함께 사용한다.
- 버전은 Spring Boot 3.5.x 호환 여부를 Maven Central에서 확인 후 업데이트할 것.
