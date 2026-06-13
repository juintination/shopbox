package com.example.shopbox.outbox.service

import com.example.shopbox.common.exception.BusinessException
import com.example.shopbox.outbox.entity.DeadLetterEvent
import com.example.shopbox.outbox.repository.DeadLetterEventRepository
import com.example.shopbox.outbox.repository.OutboxEventRepository
import com.navercorp.fixturemonkey.FixtureMonkey
import com.navercorp.fixturemonkey.kotlin.KotlinPlugin
import com.navercorp.fixturemonkey.kotlin.giveMeKotlinBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.Optional

class DeadLetterServiceTest : BehaviorSpec({

    val deadLetterEventRepository = mockk<DeadLetterEventRepository>()
    val outboxEventRepository = mockk<OutboxEventRepository>()

    val deadLetterService = DeadLetterService(
        deadLetterEventRepository = deadLetterEventRepository,
        outboxEventRepository = outboxEventRepository,
    )

    val fixtureMonkey = FixtureMonkey.builder()
        .plugin(KotlinPlugin())
        .build()

    val sampleEvent = fixtureMonkey.giveMeKotlinBuilder<DeadLetterEvent>()
        .set(DeadLetterEvent::id, 1L)
        .set(DeadLetterEvent::outboxEventId, 100L)
        .set(DeadLetterEvent::aggregateType, "Order")
        .set(DeadLetterEvent::aggregateId, 1L)
        .set(DeadLetterEvent::eventType, "OrderCreated")
        .set(DeadLetterEvent::payload, """{"orderId":1}""")
        .set(DeadLetterEvent::errorMessage, "Kafka timeout")
        .setNull(DeadLetterEvent::deletedAt)
        .sample()

    beforeEach { clearAllMocks() }

    Given("dead_letter_events에 미재처리 이벤트가 있을 때") {
        When("findAll()을 호출하면") {
            Then("deleted_at IS NULL인 이벤트 목록을 반환한다") {
                every { deadLetterEventRepository.findAll() } returns listOf(sampleEvent)

                val result = deadLetterService.findAll()

                result.size shouldBe 1
                result.first().aggregateType shouldBe "Order"
                result.first().errorMessage shouldBe "Kafka timeout"
            }
        }
    }

    Given("dead_letter_events에 이벤트가 있을 때") {
        When("retry(id)를 호출하면") {
            Then("outbox_events에 retry_count=0으로 재등록되고 DLQ 이벤트가 Soft delete된다") {
                every { deadLetterEventRepository.findById(1L) } returns Optional.of(sampleEvent)
                val outboxSavedSlot = slot<com.example.shopbox.outbox.entity.OutboxEvent>()
                every { outboxEventRepository.save(capture(outboxSavedSlot)) } answers { firstArg() }
                val dlqSavedSlot = slot<DeadLetterEvent>()
                every { deadLetterEventRepository.save(capture(dlqSavedSlot)) } answers { firstArg() }

                val result = deadLetterService.retry(1L)

                verify(exactly = 1) { outboxEventRepository.save(any()) }
                verify(exactly = 1) { deadLetterEventRepository.save(any<DeadLetterEvent>()) }
                outboxSavedSlot.captured.retryCount shouldBe 0
                outboxSavedSlot.captured.aggregateType shouldBe "Order"
                dlqSavedSlot.captured.deletedAt shouldNotBe null
                result.outboxEventId shouldBe 100L
            }
        }
    }

    Given("dead_letter_events에 해당 id의 이벤트가 없을 때") {
        When("retry(없는 id)를 호출하면") {
            Then("BusinessException이 발생한다") {
                every { deadLetterEventRepository.findById(999L) } returns Optional.empty()

                shouldThrow<BusinessException> {
                    deadLetterService.retry(999L)
                }
            }
        }
    }
})
