package com.example.shopbox.outbox.relay

import com.example.shopbox.order.event.OrderCreatedEvent
import com.example.shopbox.outbox.entity.DeadLetterEvent
import com.example.shopbox.outbox.entity.OutboxEvent
import com.example.shopbox.outbox.repository.DeadLetterEventRepository
import com.example.shopbox.outbox.repository.OutboxEventRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.kafka.core.KafkaTemplate
import java.util.concurrent.CompletableFuture

class MessageRelayTest : BehaviorSpec({

    val outboxEventRepository = mockk<OutboxEventRepository>()
    val deadLetterEventRepository = mockk<DeadLetterEventRepository>()
    val kafkaTemplate = mockk<KafkaTemplate<String, String>>()

    val maxRetry = 5

    val messageRelay = MessageRelay(
        outboxEventRepository = outboxEventRepository,
        deadLetterEventRepository = deadLetterEventRepository,
        kafkaTemplate = kafkaTemplate,
        maxRetry = maxRetry,
    )

    val pendingEvent = OutboxEvent(
        id = 1L,
        aggregateType = OrderCreatedEvent.AGGREGATE_TYPE,
        aggregateId = 1L,
        eventType = OrderCreatedEvent.EVENT_TYPE,
        payload = """{"eventId":"e1","eventType":"${OrderCreatedEvent.EVENT_TYPE}","occurredAt":"2026-06-06T00:00:00Z","orderId":1}""",
    )

    beforeEach { clearAllMocks() }

    Given("미처리 outbox_events 가 N건 있을 때") {
        When("Message Relay가 실행되면") {
            Then("N건 모두 Kafka에 발행되고 processed_at이 업데이트된다") {
                every { outboxEventRepository.findByProcessedAtIsNullAndRetryCountLessThan(maxRetry) } returns listOf(pendingEvent)
                every { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) } returns
                        CompletableFuture.completedFuture(mockk())
                val savedSlot = slot<OutboxEvent>()
                every { outboxEventRepository.save(capture(savedSlot)) } answers { firstArg() }

                messageRelay.relay()

                verify(exactly = 1) { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) }
                verify(exactly = 1) { outboxEventRepository.save(any()) }
                savedSlot.captured.processedAt shouldNotBe null
                savedSlot.captured.retryCount shouldBe 0
            }
        }
    }

    Given("Kafka 발행 중 오류가 발생하고 retry_count < max-retry 인 상황일 때") {
        When("Message Relay가 실행되면") {
            Then("retry_count가 1 증가하고 processed_at은 업데이트되지 않는다") {
                val retryableEvent = OutboxEvent(
                    id = 2L,
                    aggregateType = OrderCreatedEvent.AGGREGATE_TYPE,
                    aggregateId = 1L,
                    eventType = OrderCreatedEvent.EVENT_TYPE,
                    payload = "{}",
                    retryCount = 2,
                )
                every { outboxEventRepository.findByProcessedAtIsNullAndRetryCountLessThan(maxRetry) } returns listOf(retryableEvent)
                every { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) } throws RuntimeException("Kafka unavailable")
                val savedSlot = slot<OutboxEvent>()
                every { outboxEventRepository.save(capture(savedSlot)) } answers { firstArg() }

                messageRelay.relay()

                verify(exactly = 1) { outboxEventRepository.save(any()) }
                verify(exactly = 0) { deadLetterEventRepository.save(any<DeadLetterEvent>()) }
                savedSlot.captured.retryCount shouldBe 3
                savedSlot.captured.processedAt shouldBe null
            }
        }
    }

    Given("Kafka 발행 중 오류가 발생하고 retry_count >= max-retry 인 상황일 때") {
        When("Message Relay가 실행되면") {
            Then("dead_letter_events에 저장되고 outbox_events의 processed_at이 업데이트된다") {
                val exhaustedEvent = OutboxEvent(
                    id = 3L,
                    aggregateType = OrderCreatedEvent.AGGREGATE_TYPE,
                    aggregateId = 1L,
                    eventType = OrderCreatedEvent.EVENT_TYPE,
                    payload = "{}",
                    retryCount = maxRetry,
                )
                every { outboxEventRepository.findByProcessedAtIsNullAndRetryCountLessThan(maxRetry) } returns listOf(exhaustedEvent)
                every { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) } throws RuntimeException("Kafka down")
                val outboxSavedSlot = slot<OutboxEvent>()
                every { outboxEventRepository.save(capture(outboxSavedSlot)) } answers { firstArg() }
                val dlqSavedSlot = slot<DeadLetterEvent>()
                every { deadLetterEventRepository.save(capture(dlqSavedSlot)) } answers { firstArg() }

                messageRelay.relay()

                verify(exactly = 1) { deadLetterEventRepository.save(any<DeadLetterEvent>()) }
                verify(exactly = 1) { outboxEventRepository.save(any()) }
                dlqSavedSlot.captured.outboxEventId shouldBe 3L
                dlqSavedSlot.captured.errorMessage shouldNotBe null
                outboxSavedSlot.captured.processedAt shouldNotBe null
            }
        }
    }

    Given("max-retry = 0으로 설정된 상황에서 Kafka 발행에 실패할 때") {
        When("Message Relay가 실행되면") {
            Then("첫 번째 실패에서 즉시 DLQ로 이동한다") {
                val zeroRetryRelay = MessageRelay(
                    outboxEventRepository = outboxEventRepository,
                    deadLetterEventRepository = deadLetterEventRepository,
                    kafkaTemplate = kafkaTemplate,
                    maxRetry = 0,
                )
                val event = OutboxEvent(
                    id = 4L,
                    aggregateType = OrderCreatedEvent.AGGREGATE_TYPE,
                    aggregateId = 1L,
                    eventType = OrderCreatedEvent.EVENT_TYPE,
                    payload = "{}",
                    retryCount = 0,
                )
                every { outboxEventRepository.findByProcessedAtIsNullAndRetryCountLessThan(0) } returns listOf(event)
                every { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) } throws RuntimeException("Kafka down")
                val outboxSlot = slot<OutboxEvent>()
                every { outboxEventRepository.save(capture(outboxSlot)) } answers { firstArg() }
                val dlqSlot = slot<DeadLetterEvent>()
                every { deadLetterEventRepository.save(capture(dlqSlot)) } answers { firstArg() }

                zeroRetryRelay.relay()

                verify(exactly = 1) { deadLetterEventRepository.save(any<DeadLetterEvent>()) }
                outboxSlot.captured.processedAt shouldNotBe null
            }
        }
    }

    Given("미처리 outbox_events 가 없을 때") {
        When("Message Relay가 실행되면") {
            Then("아무 동작도 하지 않는다") {
                every { outboxEventRepository.findByProcessedAtIsNullAndRetryCountLessThan(maxRetry) } returns emptyList()

                messageRelay.relay()

                verify(exactly = 0) { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) }
                verify(exactly = 0) { outboxEventRepository.save(any()) }
            }
        }
    }
})
