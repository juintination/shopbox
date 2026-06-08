# Implementation Plan: Concurrency Control

**Branch**: `002-concurrency-control` | **Date**: 2026-06-08 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/002-concurrency-control/spec.md`

## Summary

낙관적 락(`@Version` + 재시도), 비관적 락(`SELECT FOR UPDATE`), 분산락(Redisson AOP) 세 전략을 Strategy 패턴으로 구현해 주문 중복 방지(US1)와 재고 Overselling 방지(US2)를 검증한다. 전략 교체는 `application.yml` 설정 변경만으로 가능하며, 동일 부하 조건에서 세 전략의 성능을 비교한다(US3).

## Technical Context

| Key | Value |
|-----|-------|
| Language/Version | Kotlin 2.x (JVM 21) |
| Primary Dependencies | Spring Boot 3.5.x, Spring Data JPA, `redisson-spring-boot-starter:4.5.0`, Kotest, Mockk, Fixture Monkey, Testcontainers (MySQL + Redis) |
| Storage | MySQL (기존) + Redis (분산락, 신규) |
| Testing | Kotest BehaviorSpec + Mockk (단위) + Testcontainers (동시성·성능 통합) |
| Target Platform | JVM Spring Boot 단일 모듈 |
| Project Type | web-service |
| Performance Goals | 처리 시간(ms), TPS, 성공/실패 건수 측정 및 비교 — 절대 임곗값 없음 (학습 목적) |
| Constraints | 기존 Outbox/Inbox 패턴 호환 유지, 단일 모듈, 단일 Redis 노드 (RedLock 미적용) |
| Scale/Scope | 학습 목적, 200 동시 스레드 기준 측정 |

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Test-First (TDD) | ✅ PASS | Red → Green → Refactor 사이클, `./gradlew test` 필수 |
| II. Test Scope | ⚠️ DEVIATION | 동시성 통합 테스트가 ServiceTest(Mockk) 범주에서 벗어남 → Complexity Tracking 참조 |
| III. DDD Bounded Context | ✅ PASS | 기존 컨텍스트 내 `strategy/` 서브패키지, 경계 유지 |
| IV. Outbox/Inbox/Relay | ✅ PASS | `InventoryService` Kafka Consumer 흐름 내에서 전략 추가; 기존 패턴 무영향 |
| V. Package Structure | ✅ PASS | `strategy/`는 `service/` 하위, `common/lock/`은 공유 인프라 |
| VI. ID Policy (TSID) | ✅ PASS | `Stock` 엔티티 `@Tsid` 적용 |
| Soft Delete | ✅ PASS | `Stock` 엔티티 `BaseEntity` 상속 |
| Entity Naming | ✅ PASS | `Stock` — `Entity` 접미사 없음 |
| Entity Creation | ✅ PASS | `private constructor` + `create()` 팩토리 메서드 |
| Test Fixture | ✅ PASS | Fixture Monkey with KotlinPlugin + `giveMeKotlinBuilder` |
| Coding Convention | ✅ PASS | 파라미터 줄바꿈, trailing comma, 상수 추출 |

## Project Structure

### Documentation (this feature)

```text
specs/002-concurrency-control/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── lock-strategies.md
└── tasks.md             ← /speckit-tasks 실행 시 생성
```

### Source Code Changes

**New files**:

```text
src/main/kotlin/com/example/shopbox/
├── common/
│   └── lock/
│       ├── DistributedLock.kt                     ← Redisson AOP 어노테이션
│       └── DistributedLockAspect.kt               ← 락 획득/해제 Aspect
├── inventory/
│   ├── entity/
│   │   └── Stock.kt                               ← 재고 엔티티 (신규, @Version)
│   ├── repository/
│   │   └── StockRepository.kt
│   └── service/
│       └── strategy/
│           ├── InventoryLockStrategy.kt            ← 인터페이스
│           ├── InventoryOptimisticLockStrategy.kt
│           ├── InventoryPessimisticLockStrategy.kt
│           └── InventoryDistributedLockStrategy.kt
└── order/
    └── service/
        └── strategy/
            ├── OrderLockStrategy.kt                ← 인터페이스
            ├── OrderOptimisticLockStrategy.kt
            ├── OrderPessimisticLockStrategy.kt
            └── OrderDistributedLockStrategy.kt

src/test/kotlin/com/example/shopbox/
├── inventory/
│   └── service/
│       └── strategy/
│           ├── InventoryOptimisticLockTest.kt       ← 동시성 통합 (Testcontainers)
│           ├── InventoryPessimisticLockTest.kt
│           ├── InventoryDistributedLockTest.kt
│           └── InventoryLockPerformanceTest.kt      ← 성능 비교 (Testcontainers)
└── order/
    └── service/
        └── strategy/
            ├── OrderOptimisticLockTest.kt
            ├── OrderPessimisticLockTest.kt
            └── OrderDistributedLockTest.kt
```

**Modified files**:

```text
src/main/kotlin/com/example/shopbox/
├── inventory/service/InventoryService.kt            ← 전략에 재고 차감 위임
└── order/service/OrderService.kt                   ← 전략에 주문 생성 위임

build.gradle.kts                                    ← redisson, testcontainers:redis 추가
src/main/resources/application.yml                  ← lock-strategy 설정 추가
```

**Structure Decision**: 동시성 테스트는 `strategy/` 서브패키지에 배치해 기존 `*ServiceTest.kt`(Mockk)와 명확히 분리한다. `common/lock/`은 두 컨텍스트가 `DistributedLockAspect`를 공유하기 위한 위치다.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| **Test Scope (II)**: `{Domain}LockConcurrencyTest` — Controller 없이 Service + Testcontainers | JPA `@Version` 충돌, `SELECT FOR UPDATE`, Redis 락 획득은 Mockk으로 재현 불가 — 실제 MySQL/Redis 필수 | Mockk 대체 시 락 경쟁·버전 증가·Redis 락 타임아웃 등 동시성의 핵심 학습 목표를 달성할 수 없음 |
