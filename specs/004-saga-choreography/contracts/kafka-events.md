# Kafka Event Contracts: Saga Pattern (Choreography)

**Branch**: `004-saga-choreography` | **Date**: 2026-06-15

---

## 공통 스키마

모든 이벤트는 다음 공통 필드를 포함한다.

```json
{
  "eventId": "string (TSID)",
  "eventType": "string",
  "occurredAt": "string (ISO 8601, e.g. 2026-06-15T10:00:00Z)"
}
```

보상 이벤트(`CompensationEvent`)는 추가로 `reason` 필드를 포함한다.

```json
{
  "eventId": "...",
  "eventType": "...",
  "occurredAt": "...",
  "reason": "string (실패 원인)"
}
```

---

## 정상 이벤트 (기존, 참고용)

### OrderCreated

- **Topic**: `order-events`
- **Publisher**: Order 서비스
- **Consumer**: Payment 서비스

```json
{
  "eventId": "0FGRA1WVZM000",
  "eventType": "OrderCreated",
  "occurredAt": "2026-06-15T10:00:00Z",
  "orderId": 123456789,
  "userId": 1,
  "productId": 42,
  "quantity": 3
}
```

---

### PaymentCompleted

- **Topic**: `payment-events`
- **Publisher**: Payment 서비스
- **Consumer**: Inventory 서비스

```json
{
  "eventId": "0FGRA1WVZM001",
  "eventType": "PaymentCompleted",
  "occurredAt": "2026-06-15T10:00:01Z",
  "paymentId": 234567890,
  "orderId": 123456789,
  "amount": 30000,
  "productId": 42,
  "quantity": 3
}
```

---

### StockReserved

- **Topic**: `inventory-events`
- **Publisher**: Inventory 서비스
- **Consumer**: Delivery 서비스

```json
{
  "eventId": "0FGRA1WVZM002",
  "eventType": "StockReserved",
  "occurredAt": "2026-06-15T10:00:02Z",
  "inventoryId": 345678901,
  "orderId": 123456789
}
```

---

### DeliveryStarted

- **Topic**: `delivery-events`
- **Publisher**: Delivery 서비스
- **Consumer**: Order 서비스

```json
{
  "eventId": "0FGRA1WVZM003",
  "eventType": "DeliveryStarted",
  "occurredAt": "2026-06-15T10:00:03Z",
  "deliveryId": 456789012,
  "orderId": 123456789
}
```

---

## 보상 이벤트 (신규)

### PaymentFailed

- **Topic**: `payment-events`
- **Publisher**: Payment 서비스
- **Consumer**: Order 서비스

```json
{
  "eventId": "0FGRA1WVZM010",
  "eventType": "PaymentFailed",
  "occurredAt": "2026-06-15T10:00:01Z",
  "reason": "결제 처리 중 오류가 발생했습니다",
  "paymentId": 234567890,
  "orderId": 123456789
}
```

---

### PaymentRefunded

- **Topic**: `payment-events`
- **Publisher**: Payment 서비스
- **Consumer**: Order 서비스

```json
{
  "eventId": "0FGRA1WVZM011",
  "eventType": "PaymentRefunded",
  "occurredAt": "2026-06-15T10:00:05Z",
  "reason": "재고 예약 실패로 인한 환불",
  "paymentId": 234567890,
  "orderId": 123456789
}
```

---

### StockReservationFailed

- **Topic**: `inventory-events`
- **Publisher**: Inventory 서비스
- **Consumer**: Payment 서비스

```json
{
  "eventId": "0FGRA1WVZM020",
  "eventType": "StockReservationFailed",
  "occurredAt": "2026-06-15T10:00:03Z",
  "reason": "재고 부족: productId=42, 요청 수량=3, 재고=0",
  "orderId": 123456789,
  "productId": 42,
  "quantity": 3
}
```

---

### StockRestored

- **Topic**: `inventory-events`
- **Publisher**: Inventory 서비스
- **Consumer**: Payment 서비스

```json
{
  "eventId": "0FGRA1WVZM021",
  "eventType": "StockRestored",
  "occurredAt": "2026-06-15T10:00:06Z",
  "reason": "배송 실패로 인한 재고 복구",
  "inventoryId": 345678901,
  "orderId": 123456789,
  "productId": 42,
  "quantity": 3
}
```

---

### DeliveryFailed

- **Topic**: `delivery-events`
- **Publisher**: Delivery 서비스
- **Consumer**: Inventory 서비스

```json
{
  "eventId": "0FGRA1WVZM030",
  "eventType": "DeliveryFailed",
  "occurredAt": "2026-06-15T10:00:04Z",
  "reason": "배송 처리 중 오류가 발생했습니다",
  "deliveryId": 456789012,
  "orderId": 123456789
}
```

---

### OrderCancelled

- **Topic**: `order-events`
- **Publisher**: Order 서비스
- **Consumer**: 없음 (Saga 종료 이벤트)

```json
{
  "eventId": "0FGRA1WVZM040",
  "eventType": "OrderCancelled",
  "occurredAt": "2026-06-15T10:00:06Z",
  "reason": "결제 실패로 인한 주문 취소",
  "orderId": 123456789
}
```

---

## 토픽별 이벤트 매핑

| Topic              | 이벤트                                         |
|--------------------|----------------------------------------------|
| `order-events`     | `OrderCreated`, `OrderCancelled`             |
| `payment-events`   | `PaymentCompleted`, `PaymentFailed`, `PaymentRefunded` |
| `inventory-events` | `StockReserved`, `StockReservationFailed`, `StockRestored` |
| `delivery-events`  | `DeliveryStarted`, `DeliveryFailed`          |

---

## Consumer Group 매핑

| Consumer Group                  | 구독 토픽           | 처리 서비스       |
|---------------------------------|-----------------|--------------|
| `shopbox-payment-consumer`      | `order-events`     | Payment      |
| `shopbox-inventory-consumer`    | `payment-events`   | Inventory    |
| `shopbox-delivery-consumer`     | `inventory-events` | Delivery     |
| `shopbox-order-saga-consumer`   | `payment-events`   | Order (신규)  |
| `shopbox-order-delivery-consumer` | `delivery-events` | Order (신규) |
| `shopbox-payment-saga-consumer` | `inventory-events` | Payment (신규)|
| `shopbox-inventory-saga-consumer` | `delivery-events` | Inventory (신규)|
