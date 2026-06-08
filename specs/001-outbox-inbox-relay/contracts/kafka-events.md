# Kafka Event Contracts

**Version**: 1.0.0 | **Branch**: `001-outbox-inbox-relay`

All events are serialised to JSON and stored as the `payload` field in `outbox_events`.
The Kafka message key is the `eventId` field for consumer-side idempotency lookup.

---

## Common Envelope

Every event carries this top-level envelope:

```json
{
  "eventId":    "<UUID or TSID string — unique per event instance>",
  "eventType":  "<PascalCase event name>",
  "occurredAt": "<ISO 8601 UTC — e.g. 2026-06-06T13:00:00Z>",
  "payload":    { /* event-specific fields — see below */ }
}
```

| Field        | Type   | Constraint              |
|--------------|--------|-------------------------|
| `eventId`    | String | Non-null, unique        |
| `eventType`  | String | Non-null, versioned key |
| `occurredAt` | String | ISO 8601, UTC           |
| `payload`    | Object | Non-null                |

---

## OrderCreated

**Topic**: `order-events`  
**Produced by**: Message Relay (from `outbox_events` where `aggregate_type = 'Order'`)  
**Consumed by**: Payment service (`shopbox-payment-consumer`)

```json
{
  "eventId":    "01J4K2T...",
  "eventType":  "OrderCreated",
  "occurredAt": "2026-06-06T13:00:00Z",
  "payload": {
    "orderId":   12345678901234,
    "userId":    98765432109876,
    "productId": 11111111111111,
    "quantity":  2
  }
}
```

| Payload Field | Type   | Constraint |
|---------------|--------|------------|
| `orderId`     | Long   | Non-null   |
| `userId`      | Long   | Non-null   |
| `productId`   | Long   | Non-null   |
| `quantity`    | Int    | ≥ 1        |

---

## PaymentCompleted

**Topic**: `payment-events`  
**Produced by**: Message Relay (from `outbox_events` where `aggregate_type = 'Payment'`)  
**Consumed by**: Inventory service (`shopbox-inventory-consumer`)

```json
{
  "eventId":    "01J4K2T...",
  "eventType":  "PaymentCompleted",
  "occurredAt": "2026-06-06T13:00:01Z",
  "payload": {
    "paymentId": 22222222222222,
    "orderId":   12345678901234,
    "amount":    29800
  }
}
```

| Payload Field | Type | Constraint |
|---------------|------|------------|
| `paymentId`   | Long | Non-null   |
| `orderId`     | Long | Non-null   |
| `amount`      | Long | > 0        |

---

## StockReserved

**Topic**: `inventory-events`  
**Produced by**: Message Relay (from `outbox_events` where `aggregate_type = 'Inventory'`)  
**Consumed by**: Delivery service (`shopbox-delivery-consumer`)

```json
{
  "eventId":    "01J4K2T...",
  "eventType":  "StockReserved",
  "occurredAt": "2026-06-06T13:00:02Z",
  "payload": {
    "inventoryId": 33333333333333,
    "orderId":     12345678901234
  }
}
```

| Payload Field  | Type | Constraint |
|----------------|------|------------|
| `inventoryId`  | Long | Non-null   |
| `orderId`      | Long | Non-null   |

---

## DeliveryStarted

**Topic**: `inventory-events` (reuse existing topic for this demo scope)  
**Note**: In production this would be a `delivery-events` topic; kept minimal here.

```json
{
  "eventId":    "01J4K2T...",
  "eventType":  "DeliveryStarted",
  "occurredAt": "2026-06-06T13:00:03Z",
  "payload": {
    "deliveryId": 44444444444444,
    "orderId":    12345678901234
  }
}
```

---

## Kotlin Domain Event Interface

```kotlin
// common/event/DomainEvent.kt
interface DomainEvent {
    val eventId: String
    val eventType: String
    val occurredAt: String
}
```

Each bounded context defines its own event data class implementing `DomainEvent`:

```kotlin
// order/event/OrderCreatedEvent.kt
data class OrderCreatedEvent(
    override val eventId: String = UUID.randomUUID().toString(),
    override val eventType: String = "OrderCreated",
    override val occurredAt: String = Instant.now().toString(),
    val orderId: Long,
    val userId: Long,
    val productId: Long,
    val quantity: Int,
) : DomainEvent
```

---

## Versioning Policy

- Current version: `1.0.0`
- Breaking changes (field removal, type change, rename) → MAJOR bump + migration period
- Additive changes (new optional field) → MINOR bump
- The `eventType` string serves as the version discriminator in `outbox_events.event_type`
