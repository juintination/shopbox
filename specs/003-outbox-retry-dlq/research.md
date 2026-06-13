# Research: Outbox Retry & Dead Letter Queue

## Decision 1: retry_count 증가 방식

**Decision**: `OutboxEvent`에 `var retryCount: Int = 0` 필드를 추가하고 JPA `save()`로 증가시킨다.

**Rationale**: 기존 `processedAt` 업데이트 패턴과 동일하게 `var` 필드 + `save()`를 사용한다. 별도 쿼리(bulk update)보다 단순하며, 이벤트 단위 처리가 이미 for-each 루프 구조이므로 N+1 문제 없음.

**Alternatives considered**:
- `@Modifying @Query` bulk UPDATE → 대량 처리 시 효율적이지만 현재 구조(이벤트별 Kafka 발행 결과 추적)와 맞지 않음.

---

## Decision 2: 폴링 쿼리 변경 방식

**Decision**: `OutboxEventRepository`에 Spring Data JPA 파생 쿼리를 추가한다.

```kotlin
fun findByProcessedAtIsNullAndRetryCountLessThan(maxRetry: Int): List<OutboxEvent>
```

**Rationale**: 기존 `findByProcessedAtIsNull()`과 동일한 Spring Data JPA 파생 쿼리 스타일을 유지한다. `max-retry` 값을 런타임에 주입받아 쿼리에 전달하는 방식이 가장 단순하다.

**Alternatives considered**:
- `@Query` JPQL 직접 작성 → 기능상 동일하지만 파생 쿼리로 충분하므로 불필요한 복잡도.

---

## Decision 3: max-retry 설정 주입 방식

**Decision**: `@Value("\${outbox.relay.max-retry:5}")`로 `MessageRelay`에 직접 주입한다.

**Rationale**: 단일 값 설정이므로 `@ConfigurationProperties` 클래스를 따로 만들 필요가 없다. 기존 `fixedDelayString = "\${outbox.relay.interval:5000}"` 패턴과 동일하게 `@Value` 사용.

**Alternatives considered**:
- `@ConfigurationProperties(prefix = "outbox.relay")` → `interval`과 `maxRetry`를 함께 관리할 수 있어 더 명시적이지만, 현재 `interval`도 `@Scheduled`의 `fixedDelayString`으로 `@Value` 없이 처리되므로 일관성을 위해 `@Value` 선택.

---

## Decision 4: DeadLetterEvent 설계

**Decision**: `BaseEntity`를 상속하고 `@SQLRestriction` + `@SQLDelete`를 적용한다.

**Rationale**: Constitution의 Soft Delete 원칙 준수. `deleted_at`이 재처리 완료 시각이라는 의미를 가지므로 이력 보존 목적에도 정확히 부합한다. `OutboxEvent`는 append-only라 `BaseEntity`를 상속하지 않지만, `DeadLetterEvent`는 Soft delete가 의미 있는 상태 변화(재처리 완료)를 표현하므로 상속이 적절하다.

**Alternatives considered**:
- `retried_at` 별도 컬럼 → `deleted_at`(BaseEntity)이 동일 목적을 수행하므로 중복.
- Hard delete → 재처리 이력을 잃어버리므로 요구사항 위반.

---

## Decision 5: DLQ 이동 트랜잭션 설계

**Decision**: `MessageRelay.relay()`의 이벤트 처리 루프 내에서 `DeadLetterEventRepository.save()` + `outboxEvent.processedAt 업데이트`를 하나의 흐름으로 처리한다.

**Rationale**: `relay()` 메서드는 `@Scheduled`이므로 `@Transactional` 없이 실행된다. 각 이벤트 처리가 독립적(catch로 분리)이므로, DLQ 이동과 `processedAt` 업데이트가 하나의 트랜잭션이 되도록 별도 `@Transactional` private/inner 서비스 메서드로 위임한다. 기존 `save()` 호출이 각 이벤트별로 독립적으로 동작하던 방식과 동일.

**Alternatives considered**:
- `relay()` 전체를 `@Transactional`로 감싸기 → 대량 이벤트 처리 시 트랜잭션이 너무 길어짐.

---

## Decision 6: DeadLetterController 위치 및 경로

**Decision**: `outbox/controller/DeadLetterController.kt`, 경로는 `/api/dead-letters`.

**Rationale**: DLQ는 Outbox 패턴의 운영 컴포넌트이므로 `outbox` 패키지 내에 위치하는 것이 응집도상 적절하다. 기존 `OrderController`의 `@RequestMapping("/api/orders")` 패턴과 일관성을 맞춰 `/api/dead-letters`를 사용한다.

**Alternatives considered**:
- `/dead-letters` → 기존 spec 초안의 명세였으나, 기존 API 패턴(`/api/*`)과의 일관성을 위해 `/api/dead-letters`로 변경.
