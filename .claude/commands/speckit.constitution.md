# Constitution

다음 원칙들을 기반으로 프로젝트 constitution을 작성해줘.

## 프로젝트 목적

이 프로젝트는 이커머스 도메인(주문 → 결제 → 재고 → 배송)을 활용하여
Transactional Outbox Pattern, Inbox Pattern, Message Relay를
학습 목적으로 직접 구현하는 프로젝트야.
핵심 목표는 패턴의 동작 원리를 깊이 이해하는 것이고,
추후 Saga Pattern과 동시성 제어(재고 락)로 확장 가능한 구조를 갖추는 것이야.

## 테스트 전략 (최우선 원칙)

- TDD를 엄격하게 적용한다: 실패하는 테스트 없이는 구현 코드를 작성하지 않는다
- Red → Green → Refactor 사이클을 명시적으로 유지한다
- 단위 테스트: Mockk을 사용하여 Service 레이어를 Repository로부터 격리한다
- 통합 테스트: Testcontainers로 실제 MySQL과 Kafka를 띄워서 검증한다
- 테스트 이름은 Given/When/Then 형식으로 시나리오를 명확하게 표현한다

## TDD 사이클 규칙 (NON-NEGOTIABLE)

각 태스크는 반드시 다음 순서를 따른다:

1. 🔴 Red: 테스트 코드 작성 후 `./gradlew test` 실행 → 반드시 실패해야 함
2. 🟢 Green: 최소 구현 후 `./gradlew test` 실행 → 반드시 통과해야 함
3. 🔵 Refactor: 코드 정리 후 `./gradlew test` 실행 → 여전히 통과해야 함

- `./gradlew test` 실행 없이 다음 단계로 넘어가는 것을 금지한다
- 테스트가 실패하지 않으면 구현으로 넘어가지 않는다
- 테스트가 통과하지 않으면 다음 태스크로 넘어가지 않는다

## 테스트 범위 규칙

테스트는 다음 두 가지만 작성한다:

- 단위 테스트: Service 레이어만 → `{Domain}ServiceTest.kt` (Mockk)
- 통합 테스트: Controller 레이어만 → `{Domain}ControllerTest.kt` (Testcontainers)

테스트 파일 위치 규칙: 테스트 파일은 테스트 대상 프로덕션 클래스와 동일한 서브 패키지에 위치한다.

```
order/service/OrderService.kt        → order/service/OrderServiceTest.kt
order/controller/OrderController.kt  → order/controller/OrderControllerTest.kt
outbox/relay/MessageRelay.kt         → outbox/relay/MessageRelayTest.kt
```

작성하지 않는 테스트:

- RepositoryTest (JPA가 이미 검증된 라이브러리)
- EntityTest
- DTOTest
- 그 외 세분화된 단위 테스트

## 아키텍처 원칙

- 전체 구조는 DDD의 Bounded Context 개념을 따른다
- 각 Bounded Context(Order, Payment, Inventory, Delivery) 내부는
  레이어드 아키텍처를 따른다: Controller → Service → Repository
- Context 간 통신은 Kafka 이벤트로만 한다 (직접 호출 금지)
- 각 Context는 추후 독립적인 서비스로 분리 가능한 구조를 갖춰야 한다
- 각 레이어의 책임:
    - Controller: 요청/응답 처리만 담당
    - Service: 비즈니스 로직 + 트랜잭션 관리, **반드시 DTO를 반환한다 (Entity 반환 금지)**
    - Repository: 데이터 접근 (Spring Data JPA 직접 사용)
- Service가 Entity를 반환하면 영속성 컨텍스트가 Controller까지 누수되어 예상치 못한 LazyInitializationException 또는 추가 쿼리가 발생할 수 있다. 트랜잭션 경계 안에서 DTO로 변환한 뒤 반환한다

## 패키지 구조 원칙

각 Bounded Context는 다음 패키지 구조를 따른다:

```
{context}/                       ex) order/
├── controller/                  ← API 엔드포인트
├── service/                     ← 비즈니스 로직
├── repository/                  ← 데이터 접근
├── entity/                      ← JPA Entity + 도메인 모델
│   └── enums/                   ← 도메인 관련 Enum
├── dto/
│   ├── request/                 ← 요청 DTO
│   └── response/                ← 응답 DTO
├── event/                       ← 도메인 이벤트
└── exception/                   ← Context 전용 예외
```

- Entity ↔ DTO 변환은 별도 mapper 클래스 없이 `from()` / `of()` 정적 메서드를 사용한다
- 공통 요소는 common 패키지에서 관리한다:

```
common/
├── entity/                      ← BaseEntity (공통 필드 + Soft delete)
├── exception/                   ← 공통 예외 베이스 (BusinessException)
├── event/                       ← 이벤트 공통 인터페이스 (DomainEvent)
└── dto/
    └── response/                ← 공통 응답 포맷 (ApiResponse)
```

## Entity 네이밍 규칙

- Entity 클래스명에 `Entity` 접미사를 붙이지 않는다
- 테이블명은 `@Table(name = "...")`으로 명시적으로 지정한다
- `entity/` 패키지 안에 위치하므로 접미사 없이도 역할이 명확하다

```
❌ OrderEntity, PaymentEntity
✅ order/entity/Order.kt, payment/entity/Payment.kt
```

## ID 정책

- 모든 Entity의 PK는 TSID(Time-Sorted ID)를 사용한다
- `io.hypersistence.utils.hibernate.id.Tsid` 어노테이션을 사용한다
- 컬럼 타입은 `BIGINT UNSIGNED`로 정의한다
- 시간 기반 정렬이 가능하고 분산 환경에서도 충돌 없이 생성된다
- 각 Entity에 직접 선언한다:

```kotlin
@Id
@Tsid
@Column(columnDefinition = "BIGINT UNSIGNED")
val id: Long? = null
```

- UUID가 필요한 모든 곳(이벤트 ID 등)에서 `UUID.randomUUID()` 대신 `TSID.fast().toString()`을 사용한다 — 시간순 정렬이 가능하고 프로젝트 전체의 ID 전략이 일관된다
- `io.hypersistence.tsid.TSID` 클래스는 `hypersistence-utils` 의존성에 번들로 포함되어 있어 별도 추가 없이 사용 가능하다

## Soft Delete 원칙

- 모든 Entity는 Soft delete를 적용한다
- `common/entity/` 패키지의 BaseEntity를 상속받아 공통 필드를 관리한다
    - `created_at`: 생성 시각
    - `updated_at`: 수정 시각
    - `deleted_at`: 삭제 시각 (null이면 유효한 데이터)
- `@SQLRestriction("deleted_at is null")`로 삭제된 데이터를 자동 필터링한다
- `@SQLDelete`로 DELETE 쿼리를 `UPDATE deleted_at = now()`로 대체한다
- `deleted_at` 컬럼에는 단일 인덱스를 설정한다

## 프로젝트 구조 원칙

- 단일 모듈 프로젝트로 구성한다
- 패키지로 Bounded Context 경계를 구분한다:

```
src/main/kotlin/
├── order/
├── payment/
├── inventory/
├── delivery/
├── outbox/
└── common/
```

- Context 간 직접 참조를 금지하며 Kafka 이벤트로만 소통한다
- 패키지 경계를 엄격히 지켜 추후 멀티모듈/멀티서비스 분리가 가능한 구조를 유지한다

## Outbox / Inbox / Message Relay 원칙

- Outbox: 비즈니스 데이터와 Outbox 이벤트는 반드시 같은 트랜잭션에 저장한다
- Inbox: 멱등성은 필수다 — 동일한 `message_id`는 절대 두 번 처리하지 않는다
- Message Relay: 폴링 방식과 CDC 방식 모두를 지원할 수 있는 구조로 설계한다
- 모든 메시지 계약(이벤트 스키마)은 명시적으로 정의하고 버전을 관리한다

## 기술 스택

- 언어: Kotlin 2.x
- 프레임워크: Spring Boot 3.x
- 데이터베이스: MySQL
- 메시지 브로커: Kafka
- ORM: Spring Data JPA
- Kafka 클라이언트: spring-kafka
- 테스트: Kotest (BehaviorSpec) + Mockk + Testcontainers
- TSID: `io.hypersistence:hypersistence-utils`

## 코드 품질 원칙

- 테스트 커버리지 없는 프로덕션 코드는 작성하지 않는다
- Spring의 암묵적 동작보다 명시적인 코드를 선호한다
- 각 클래스/함수는 단일 책임에 집중한다
- Saga의 보상 트랜잭션은 당장 구현하지 않더라도 처음부터 설계에 반영해둔다

## Entity 생성 원칙

- 모든 Entity는 `private constructor`로 외부에서 임의 생성을 금지한다
- 생성 진입점은 반드시 `companion object`의 `create()` 팩토리 메서드 하나뿐이다
- `create()` 내부에서만 생성자를 호출하므로, 불변식(invariant) 검증도 이 메서드에서 수행한다

```kotlin
class Order private constructor(
    val id: Long? = null,
    val userId: Long,
    ...
) : BaseEntity() {
    companion object {
        fun create(
            userId: Long,
            ...
        ) = Order(
            userId = userId,
            ...
        )
    }
}
```

- `kotlin("plugin.jpa")`가 Hibernate용 no-arg 생성자를 자동 생성하므로 `private constructor`와 충돌하지 않는다

## 테스트 픽스처 원칙

- `private constructor`가 적용된 Entity는 테스트 코드에서 직접 생성할 수 없다
- 단위 테스트에서 임의의 Entity 상태가 필요할 때는 **Fixture Monkey**를 사용한다
- Fixture Monkey는 리플렉션으로 `private constructor`를 우회해 자유롭게 필드 값을 설정한다
- `KotlinPlugin()`을 반드시 등록하고, `giveMeKotlinBuilder<T>()`로 빌더를 생성한다

```kotlin
val fixtureMonkey = FixtureMonkey.builder()
    .plugin(KotlinPlugin())
    .build()

// id처럼 nullable인 필드는 명시적으로 set해야 !! 호출 시 NPE를 방지할 수 있다
val order = fixtureMonkey.giveMeKotlinBuilder<Order>()
    .set(Order::id, 1L)
    .sample()
```

- `giveMeBuilder` 대신 `giveMeKotlinBuilder`를 사용한다 — Kotlin 프로퍼티 참조(`KProperty1`)를 올바르게 처리한다

## 코딩 컨벤션

- 모든 함수/생성자의 파라미터는 항상 줄바꿈하여 선언한다 (파라미터가 1개여도 동일하게 적용)
- 함수 호출 시에도 파라미터는 항상 줄바꿈하여 전달한다
- trailing comma를 항상 사용한다
- 표현식 본문 함수(`= ...`)에서 반환 타입이 우변으로부터 명확히 추론 가능하면 반환 타입 선언을 생략한다. `override` 함수는 인터페이스 계약을 명시하므로 예외로 유지한다:

```kotlin
// ❌ 중복
fun create(orderId: Long): Delivery = Delivery(orderId = orderId)

// ✅ 추론 가능하므로 생략
fun create(orderId: Long) = Delivery(orderId = orderId)
```

- 문자열 리터럴을 코드 여러 곳에서 참조하는 경우 반드시 상수로 추출한다. 이벤트 타입명, 에러 메시지 등이 대표적인 대상이다:

```kotlin
// ❌ 하드코딩
eventType = "OrderCreated"
ApiResponse.error("서버 내부 오류가 발생했습니다")

// ✅ 상수 추출
companion object {
    const val EVENT_TYPE = "OrderCreated"
}
eventType = EVENT_TYPE

companion object {
    private const val INTERNAL_SERVER_ERROR_MESSAGE = "서버 내부 오류가 발생했습니다"
}
ApiResponse.error(INTERNAL_SERVER_ERROR_MESSAGE)
```

```kotlin
// 선언
fun createOrder(
    userId: Long,
    productId: Long,
    quantity: Int,
): Order

// 호출
val order = orderService.createOrder(
    userId = userId,
    productId = productId,
    quantity = quantity,
)
```

## 입력값 검증 원칙

- 모든 요청 DTO는 Spring Validation(`@Valid`)으로 입력값을 검증한다
- 검증 어노테이션은 DTO 필드에 직접 선언한다:

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

- Controller에서 `@Valid` 어노테이션으로 검증을 활성화한다
- 검증 실패 시 공통 예외 핸들러(`GlobalExceptionHandler`)에서 일관된 에러 응답 포맷(`ApiResponse`)으로 처리한다
- 비즈니스 규칙 검증(도메인 검증)은 Service 레이어에서 처리한다
    - 입력값 형식 검증은 DTO, 비즈니스 규칙 검증은 Service로 역할 분리
