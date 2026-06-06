<!--
## Sync Impact Report

**Version**: N/A → 1.0.0 (INITIAL CREATION)

### Changes
- Initial constitution created from project principles

### Modified Principles
- None (initial creation)

### Added Sections
- Core Principles: I. Test-First, II. DDD Architecture, III. Outbox/Inbox/Relay,
  IV. Package Structure, V. ID Policy
- Development Standards: Soft Delete, Code Quality, Coding Convention, Input Validation
- Technology Stack
- Governance

### Templates Requiring Updates
- ✅ `.specify/templates/plan-template.md` — Constitution Check section references TDD + architecture gates; no structural change needed
- ✅ `.specify/templates/spec-template.md` — User story / Given-When-Then format aligns with constitution
- ⚠️ `.specify/templates/tasks-template.md` — Template marks tests as OPTIONAL; constitution mandates TDD.
  Note: The tasks template is a generic speckit template. Per this constitution, test tasks are ALWAYS required.

### Deferred Items
- None
-->

# Shopbox Constitution

## Core Principles

### I. Test-First (NON-NEGOTIABLE)

TDD MUST be strictly applied throughout this project. No production code MAY be written
without a failing test that justifies its existence.

- The Red → Green → Refactor cycle MUST be explicitly maintained on every change
- Unit tests MUST use Mockk to isolate the Service layer from its Repositories
- Integration tests MUST use Testcontainers to spin up real MySQL and Kafka instances
- Test names MUST follow **Given / When / Then** format to clearly express the scenario
- Test coverage MUST exist for all production code — untested code MUST NOT be merged

**Rationale**: This is a learning project. The core value comes from observing pattern
behavior through tests. A green test suite that was not written test-first does not
fulfill the learning goal.

### II. DDD Bounded Context Architecture

The system MUST be structured around DDD Bounded Contexts with a layered architecture
inside each context.

- Four Bounded Contexts: **Order**, **Payment**, **Inventory**, **Delivery**
- Each context MUST follow the layer sequence: Controller → Service → Repository
- Layer responsibilities (non-negotiable):
  - **Controller**: request/response handling ONLY — no business logic
  - **Service**: business logic + transaction management
  - **Repository**: data access via Spring Data JPA
- Inter-context communication MUST use Kafka events exclusively — direct package
  references across context boundaries are PROHIBITED
- Each context MUST be structured so it can be extracted as an independent service
  without structural rework

### III. Transactional Outbox / Inbox / Message Relay

Messaging pattern guarantees MUST be upheld without exception.

- **Outbox**: Business data and the corresponding Outbox event MUST be persisted in the
  same database transaction — split-write is a critical violation
- **Inbox**: Idempotency is MANDATORY — the same `message_id` MUST NEVER be processed
  more than once under any circumstances
- **Message Relay**: The design MUST support both a polling-based and a CDC
  (Change Data Capture) relay strategy without requiring structural changes
- All message contracts (event schemas) MUST be explicitly defined and carry a version

**Rationale**: These three patterns are the primary learning targets. A violation of their
core guarantees (atomicity, idempotency, at-least-once delivery) defeats the purpose of
implementing them.

### IV. Package Structure

Every Bounded Context MUST follow the prescribed internal package layout:

```
{context}/                       ex) order/
├── controller/                  ← API endpoints
├── service/                     ← business logic
├── repository/                  ← data access
├── domain/                      ← domain models
│   └── enums/                   ← domain-specific enums
├── entity/                      ← JPA entities (extend BaseEntity)
├── dto/
│   ├── request/                 ← inbound DTOs
│   └── response/                ← outbound DTOs
├── event/                       ← domain events
└── exception/                   ← context-specific exceptions
```

Shared infrastructure MUST reside in the `common/` package:

```
common/
├── entity/                      ← BaseEntity (audit fields + soft delete)
├── exception/                   ← BusinessException base
├── event/                       ← DomainEvent interface
└── dto/
    └── response/                ← ApiResponse wrapper
```

- Entity ↔ Domain and Entity ↔ DTO conversions MUST use `from()` / `of()` static methods.
  Dedicated mapper classes are PROHIBITED.
- Introducing a cross-context direct dependency requires explicit justification and a
  migration plan toward event-based communication.

### V. ID Policy (TSID)

All Entity primary keys MUST use TSID (Time-Sorted ID).

- MUST apply the `@Tsid` annotation from `io.hypersistence.utils.hibernate.id`
- Column definition MUST be `BIGINT UNSIGNED`
- Auto-increment (`@GeneratedValue`) and UUID-based IDs are PROHIBITED for PKs

```kotlin
@Id
@Tsid
@Column(columnDefinition = "BIGINT UNSIGNED")
val id: Long? = null
```

**Rationale**: TSID provides monotonically increasing, time-sortable IDs that remain
collision-free in distributed environments — a requirement for the eventual multi-service
extraction path.

## Development Standards

### Soft Delete

Every Entity MUST implement Soft Delete via `BaseEntity`.

- MUST extend `BaseEntity` from `common/entity/`
- `BaseEntity` MUST declare: `created_at`, `updated_at`, `deleted_at`
- MUST annotate with `@SQLRestriction("deleted_at is null")` for transparent filtering
- MUST annotate with `@SQLDelete(sql = "UPDATE ... SET deleted_at = now() WHERE id = ?")` to
  intercept hard deletes
- `deleted_at` MUST have a single-column index

### Code Quality

- Production code without test coverage MUST NOT be written or merged
- Explicit code MUST be preferred over Spring's implicit/convention-based behaviors
  when the behavior affects correctness or is non-obvious
- Each class and function MUST have a single, focused responsibility
- Saga compensating transactions MUST be reflected in the initial design even if their
  implementation is deferred — placeholder hooks or documented rollback points are acceptable

### Coding Convention

All function and constructor parameters MUST be declared one-per-line, including
single-parameter signatures. Trailing commas are MANDATORY.

```kotlin
// Declaration
fun createOrder(
    userId: Long,
    productId: Long,
    quantity: Int,
): Order

// Call site
val order = orderService.createOrder(
    userId = userId,
    productId = productId,
    quantity = quantity,
)
```

### Input Validation

All inbound request DTOs MUST be validated via Spring Validation.

- Validation constraints MUST be declared on DTO fields with the `@field:` prefix
- The receiving Controller method MUST carry `@Valid` on the DTO parameter
- Validation failures MUST be handled centrally by `GlobalExceptionHandler` and
  returned as a consistent `ApiResponse` error payload
- **Role separation**: input format/type validation belongs in DTOs;
  business-rule validation belongs in the Service layer

```kotlin
data class CreateOrderRequest(
    @field:NotNull(message = "사용자 ID는 필수입니다")
    val userId: Long?,

    @field:NotNull(message = "상품 ID는 필수입니다")
    val productId: Long?,

    @field:Min(value = 1, message = "수량은 1개 이상이어야 합니다")
    val quantity: Int?,
)
```

## Technology Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.x |
| Framework | Spring Boot 3.x |
| Database | MySQL |
| Message Broker | Kafka |
| ORM | Spring Data JPA |
| Kafka Client | spring-kafka |
| Test Framework | Kotest (BehaviorSpec) |
| Mocking | Mockk |
| Integration Testing | Testcontainers |
| ID Generation | io.hypersistence:hypersistence-utils (TSID) |

**Project topology** — single module, context boundaries enforced by package:

```
src/main/kotlin/
├── order/
├── payment/
├── inventory/
├── delivery/
├── outbox/
└── common/
```

Strict package discipline MUST be maintained so the structure can be split into a
multi-module or multi-service layout without restructuring.

## Governance

This Constitution supersedes all other practices and guidelines. Amendments require
documented rationale and explicit agreement.

- Every implementation plan MUST include a **Constitution Check** gate before Phase 0
  research and again after Phase 1 design
- Deviations from any principle MUST be recorded in the **Complexity Tracking** section
  of `plan.md` with justification and a rejected simpler alternative
- Package boundary violations require a documented migration plan toward event-based
  communication before the violation is permitted
- Version policy:
  - **MAJOR**: backward-incompatible governance change or principle removal/redefinition
  - **MINOR**: new principle or section added, or guidance materially expanded
  - **PATCH**: clarifications, wording fixes, non-semantic refinements
- Constitution compliance MUST be reviewed at each plan and implementation stage

**Version**: 1.0.0 | **Ratified**: 2026-06-06 | **Last Amended**: 2026-06-06
