# Research: Transactional Outbox / Inbox / Message Relay

**Branch**: `001-outbox-inbox-relay` | **Phase**: 0

## 1. Missing Build Dependencies

### Decision
Add the following dependencies to `build.gradle.kts`:

```kotlin
// Kafka
implementation("org.springframework.kafka:spring-kafka")

// Testing
testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
testImplementation("io.kotest.extensions:kotest-extensions-spring:1.3.0")
testImplementation("io.mockk:mockk:1.13.17")
testImplementation("org.springframework.boot:spring-boot-testcontainers")
testImplementation("org.testcontainers:kafka")
testImplementation("org.testcontainers:mysql")
```

### Rationale
- `spring-kafka` version is managed by Spring Boot 3.5.x BOM (→ spring-kafka 3.3.x); no explicit version required.
- `testcontainers:kafka` and `testcontainers:mysql` are similarly managed by the BOM.
- `kotest-extensions-spring` provides `SpringExtension` and `@SpringBootTest` wiring for `BehaviorSpec`.
- `mockk 1.13.17` is the latest stable release compatible with Kotlin 2.x.

### Alternatives Considered
- JUnit 5 with `@MockBean`: rejected because the constitution mandates Kotest `BehaviorSpec`.
- Embedded Kafka (`EmbeddedKafkaBroker`): rejected because Testcontainers uses the real Kafka image (4.2.0 KRaft), matching production topology.

---

## 2. Message Relay Scheduling Strategy

### Decision
Use Spring's `@Scheduled(fixedDelayString = "\${outbox.relay.interval:5000}")` with `@EnableScheduling` on the application class.

### Rationale
- Fixed-delay (not fixed-rate) ensures the next poll starts only after the previous one finishes, preventing concurrent relay runs on a single instance.
- `fixedDelayString` with an EL property reference satisfies FR-013 (externally configurable interval) without restart.
- The scheduling bean and the `KafkaTemplate` publish step are intentionally decoupled so the relay logic can be extracted into a CDC trigger without structural changes (Constitution §IV).

### Alternatives Considered
- Quartz Scheduler: rejected — adds a separate job-store table and configuration overhead; overkill for a learning project.
- Manual `Thread.sleep` loop in a `@Component` `init` block: rejected — not Spring-idiomatic and harder to test.

---

## 3. InboxEvent Idempotency — Duplicate Handling

### Decision
`inbox_events.message_id` is the PK (VARCHAR). On duplicate receipt:
1. Service calls `inboxEventRepository.save(inboxEvent)`.
2. A duplicate triggers `DataIntegrityViolationException` at the DB level.
3. Service catches the exception and returns without re-executing business logic.

```kotlin
try {
    inboxEventRepository.save(InboxEvent(messageId = messageId))
    // execute business logic
} catch (e: DataIntegrityViolationException) {
    log.info { "Duplicate message skipped: $messageId" }
}
```

### Rationale
- No extra `existsById` query before every message — save-then-catch is one round-trip under the happy path.
- The DB constraint is the source of truth; application-level checks alone cannot guarantee idempotency under concurrent consumers.

### Alternatives Considered
- `existsById` + conditional insert: rejected — TOCTOU race condition in concurrent consumers.
- `INSERT IGNORE` via native query: rejected — bypasses JPA lifecycle events and obscures intent.

---

## 4. Kafka Topic Configuration

### Decision

| Topic              | Partitions | Replication | Consumer Group                   |
|--------------------|------------|-------------|----------------------------------|
| `order-events`     | 3          | 3           | `shopbox-payment-consumer`       |
| `payment-events`   | 3          | 3           | `shopbox-inventory-consumer`     |
| `inventory-events` | 3          | 3           | `shopbox-delivery-consumer`      |

Consumer concurrency: **1** per listener container (single-threaded per topic for deterministic testing).

### Rationale
- 3 partitions × 3 replicas matches the 3-broker KRaft cluster already configured in `external/kafka/docker-compose.yml`.
- Concurrency=1 avoids race conditions on the `inbox_events` PK during integration tests; can be increased later.
- Separate consumer groups per downstream context ensures independent offset tracking.

### Alternatives Considered
- Single `shopbox-consumer` group for all topics: rejected — a single group subscribed to multiple topics means one lagging topic blocks others.
- Concurrency=3 (one thread per partition): rejected — increases risk of duplicate `inbox_events` PK violations during test; learning goal is correctness, not throughput.

---

## 5. Outbox Relay + KafkaTemplate Transaction Interaction

### Decision
The Relay does **not** use Kafka transactions (`@Transactional` on `KafkaTemplate`). It uses a two-step approach:
1. Read unprocessed `outbox_events` (no DB transaction held open).
2. Publish each event to Kafka via `KafkaTemplate.send().get()` (synchronous).
3. Only on success: update `processed_at` in a new short-lived `@Transactional` call.

### Rationale
- Holding a DB transaction open while waiting for Kafka broker ACK risks connection pool exhaustion.
- Synchronous `.get()` on the `ListenableFuture` gives a clear success/failure signal per event.
- At-least-once semantics: if the app crashes after publish but before `processed_at` update, the event is re-published. The Inbox idempotency gate handles the duplicate.
- Kafka producer transactions (`transactional.id`) add exactly-once semantics but require careful consumer isolation settings — out of scope for this learning project.

### Alternatives Considered
- Async `KafkaTemplate.send()` with callback: rejected — callback executes on a different thread, making `processed_at` update ordering non-deterministic.
- Kafka producer transactions: rejected — requires `read_committed` isolation on consumers and a transaction coordinator; scope creep for pattern learning.
