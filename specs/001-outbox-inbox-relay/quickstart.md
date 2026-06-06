# Quickstart: Transactional Outbox / Inbox / Message Relay

**Branch**: `001-outbox-inbox-relay`

## Prerequisites

| Tool         | Version  | Notes                        |
|--------------|----------|------------------------------|
| JDK          | 21       | Eclipse Temurin recommended  |
| Docker       | 24+      | For Kafka + MySQL containers |
| Gradle       | wrapper  | `./gradlew` included         |

---

## 1. Start Infrastructure

```bash
# MySQL
docker compose -f external/mysql/docker-compose.yml up -d

# Kafka (3-node KRaft cluster + kafka-ui)
docker compose -f external/kafka/docker-compose.yml up -d
```

Verify Kafka UI at `http://localhost:<KAFKA_UI_PORT>`.

---

## 2. Run the Application

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

---

## 3. Create an Order (Outbox trigger)

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId": 1, "productId": 100, "quantity": 2}'
```

Expected: `orders` and `outbox_events` rows inserted in one transaction.

---

## 4. Watch the Relay

After at most 5 seconds (relay interval), the Message Relay polls `outbox_events`
and publishes to `order-events`. Check Kafka UI or:

```bash
# Consume from order-events
docker exec -it kafka-1 \
  kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic order-events --from-beginning
```

---

## 5. Run Tests

```bash
# All tests (unit + integration)
./gradlew test

# Unit only (fast, no Docker needed)
./gradlew test --tests "*.ServiceTest"

# Integration only (requires Docker)
./gradlew test --tests "*.ControllerTest"
```

TDD workflow per Constitution §I — always run `./gradlew test` at Red → Green → Refactor.
