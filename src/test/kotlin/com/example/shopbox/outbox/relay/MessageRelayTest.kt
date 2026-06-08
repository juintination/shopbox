package com.example.shopbox.outbox.relay

import com.example.shopbox.order.event.OrderCreatedEvent
import com.example.shopbox.outbox.entity.OutboxEvent
import com.example.shopbox.outbox.repository.OutboxEventRepository
import io.kotest.core.spec.style.BehaviorSpec
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
    val kafkaTemplate = mockk<KafkaTemplate<String, String>>()

    val messageRelay = MessageRelay(
        outboxEventRepository = outboxEventRepository,
        kafkaTemplate = kafkaTemplate,
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
                every { outboxEventRepository.findByProcessedAtIsNull() } returns listOf(pendingEvent)
                every { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) } returns
                        CompletableFuture.completedFuture(mockk())
                val savedSlot = slot<OutboxEvent>()
                every { outboxEventRepository.save(capture(savedSlot)) } answers { firstArg() }

                messageRelay.relay()

                verify(exactly = 1) { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) }
                verify(exactly = 1) { outboxEventRepository.save(any()) }
                savedSlot.captured.processedAt shouldNotBe null
            }
        }
    }

    Given("Kafka 발행 중 오류가 발생하는 상황일 때") {
        When("Message Relay가 실행되면") {
            Then("processed_at이 업데이트되지 않아 다음 사이클에서 재시도된다") {
                every { outboxEventRepository.findByProcessedAtIsNull() } returns listOf(pendingEvent)
                every { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) } throws RuntimeException("Kafka unavailable")

                io.kotest.assertions.throwables.shouldNotThrow<Exception> {
                    messageRelay.relay()
                }
                verify(exactly = 0) { outboxEventRepository.save(any()) }
            }
        }
    }

    Given("미처리 outbox_events 가 없을 때") {
        When("Message Relay가 실행되면") {
            Then("아무 동작도 하지 않는다") {
                every { outboxEventRepository.findByProcessedAtIsNull() } returns emptyList()

                messageRelay.relay()

                verify(exactly = 0) { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) }
                verify(exactly = 0) { outboxEventRepository.save(any()) }
            }
        }
    }
})
