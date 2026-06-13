# Implementation Plan: Outbox Retry & Dead Letter Queue

**Branch**: `003-outbox-retry-dlq` | **Date**: 2026-06-13 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/003-outbox-retry-dlq/spec.md`

## Summary

기존 `MessageRelay`의 단순 재시도 구조를 개선하여 `retry_count`를 추적하고,
최대 재시도 횟수(`outbox.relay.max-retry`) 초과 시 `dead_letter_events` 테이블로 격리한다.
격리된 이벤트는 `GET /dead-letters` / `POST /dead-letters/{id}/retry` API로 조회·재처리한다.

## Technical Context

**Language/Version**: Kotlin 2.x  
**Primary Dependencies**: Spring Boot 3.x, Spring Data JPA, spring-kafka, Redisson  
**Storage**: MySQL (`outbox_events` 변경, `dead_letter_events` 신규)  
**Testing**: Kotest (BehaviorSpec) + Mockk (단위) + Testcontainers (통합)  
**Target Platform**: JVM / Linux server  
**Project Type**: Single-module Spring Boot web service  
**Performance Goals**: 기존 Relay 폴링 성능 유지 (이벤트별 DB 접근 횟수 최소화)  
**Constraints**: 기존 `OutboxEvent` 레코드 삭제 금지 (이력 보존), DLQ 이동 원자성 보장  
**Scale/Scope**: 기존 단일 모듈 구조 내 `outbox` 패키지 확장

## Constitution Check

### Pre-Phase 0 Gate

| 원칙 | 검토 | 결과 |
|------|------|------|
| TDD 적용 — 실패 테스트 없이 구현 금지 | `MessageRelayTest`, `DeadLetterControllerTest` 먼저 작성 | PASS |
| 테스트 범위 — ServiceTest(Mockk) + ControllerTest(Testcontainers)만 허용 | `MessageRelayTest`(단위), `DeadLetterControllerTest`(통합)으로 구성 | PASS |
| DDD Bounded Context — 패키지 경계 준수 | DLQ 기능 전체를 `outbox/` 패키지 내에 위치 | PASS |
| Service → DTO 반환 (Entity 반환 금지) | `DeadLetterService`가 `DeadLetterResponse` DTO 반환 | PASS |
| Entity 생성 — private constructor + create() | `DeadLetterEvent.create()` factory method 사용 | PASS |
| Soft Delete — BaseEntity 상속, @SQLRestriction, @SQLDelete | `DeadLetterEvent`에 적용, `OutboxEvent`는 append-only라 제외 유지 | PASS |
| TSID — 모든 Entity PK | `DeadLetterEvent.id`에 `@Tsid` 적용 | PASS |
| Coding Convention — 파라미터 줄바꿈, trailing comma | 모든 코드에서 준수 | PASS |

### Post-Phase 1 Gate

| 원칙 | 검토 | 결과 |
|------|------|------|
| 구현 전 기존 코드 읽기 | `OutboxEvent`, `MessageRelay`, `OutboxEventRepository`, `OrderController` 파악 완료 | PASS |
| Entity 네이밍 — Entity 접미사 금지 | `DeadLetterEvent` (접미사 없음) | PASS |
| 패키지 구조 준수 | `outbox/entity/`, `outbox/service/`, `outbox/controller/`, `outbox/repository/`, `outbox/dto/` | PASS |

## Project Structure

### Documentation (this feature)

```text
specs/003-outbox-retry-dlq/
├── plan.md              ← This file
├── spec.md              ← Feature specification
├── research.md          ← Phase 0 decisions
├── data-model.md        ← Entity design
├── quickstart.md        ← Integration scenarios
├── contracts/
│   └── dead-letter-api.md  ← API contracts
├── checklists/
│   └── requirements.md  ← Spec quality checklist
└── tasks.md             ← Phase 2 output (/speckit-tasks command)
```

### Source Code (변경/신규 파일)

```text
src/main/kotlin/com/example/shopbox/
├── outbox/
│   ├── entity/
│   │   ├── OutboxEvent.kt                    ← 기존 변경 (retry_count 추가)
│   │   └── DeadLetterEvent.kt                ← 신규
│   ├── repository/
│   │   ├── OutboxEventRepository.kt          ← 기존 변경 (폴링 쿼리 변경)
│   │   └── DeadLetterEventRepository.kt      ← 신규
│   ├── service/
│   │   └── DeadLetterService.kt              ← 신규
│   ├── relay/
│   │   └── MessageRelay.kt                   ← 기존 변경 (retry_count 증가, DLQ 이동)
│   ├── controller/
│   │   └── DeadLetterController.kt           ← 신규
│   └── dto/
│       └── response/
│           └── DeadLetterResponse.kt         ← 신규

src/main/resources/
└── application.yaml                          ← 기존 변경 (max-retry 추가)

src/test/kotlin/com/example/shopbox/
├── outbox/
│   ├── relay/
│   │   └── MessageRelayTest.kt               ← 기존 변경 (retry/DLQ 시나리오 추가)
│   └── controller/
│       └── DeadLetterControllerTest.kt       ← 신규
```

## Complexity Tracking

Constitution 위반 없음. 추가 항목 없음.
