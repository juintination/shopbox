package com.example.shopbox.inventory.service

import com.example.shopbox.inbox.repository.InboxEventRepository
import com.example.shopbox.inventory.entity.Inventory
import com.example.shopbox.inventory.repository.InventoryRepository
import com.example.shopbox.outbox.repository.OutboxEventRepository
import com.example.shopbox.payment.event.PaymentCompletedEvent
import com.navercorp.fixturemonkey.FixtureMonkey
import com.navercorp.fixturemonkey.kotlin.KotlinPlugin
import com.navercorp.fixturemonkey.kotlin.giveMeKotlinBuilder
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class InventoryServiceTest : BehaviorSpec({

    val fixtureMonkey = FixtureMonkey.builder()
        .plugin(KotlinPlugin())
        .build()

    val inventoryRepository = mockk<InventoryRepository>()
    val inboxEventRepository = mockk<InboxEventRepository>()
    val outboxEventRepository = mockk<OutboxEventRepository>()
    val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()

    val inventoryService = InventoryService(
        inventoryRepository = inventoryRepository,
        inboxEventRepository = inboxEventRepository,
        outboxEventRepository = outboxEventRepository,
        objectMapper = objectMapper,
    )

    val validPayload = """
        {
          "eventId": "pay-001",
          "eventType": "${PaymentCompletedEvent.EVENT_TYPE}",
          "occurredAt": "2026-06-06T13:00:00Z",
          "paymentId": 1,
          "orderId": 1,
          "amount": 0
        }
    """.trimIndent()

    beforeEach { clearAllMocks() }

    Given("message_id = 'pay-001' 이 inbox에 없을 때") {
        When("PaymentCompleted 이벤트가 수신되면") {
            Then("재고 처리 비즈니스 로직이 실행되고 inbox_events에 기록된다") {
                every { inboxEventRepository.saveIfAbsent(any()) } returns true
                every { inventoryRepository.save(any()) } returns fixtureMonkey.giveMeKotlinBuilder<Inventory>()
                    .set(Inventory::id, 1L)
                    .sample()
                every { outboxEventRepository.save(any()) } answers { firstArg() }

                shouldNotThrow<Exception> {
                    inventoryService.processPaymentCompleted(
                        messageId = "pay-001",
                        payload = validPayload,
                    )
                }
                verify(exactly = 1) { inboxEventRepository.saveIfAbsent(any()) }
                verify(exactly = 1) { inventoryRepository.save(any()) }
            }
        }
    }

    Given("message_id = 'pay-001' 이 이미 inbox에 있을 때") {
        When("동일한 PaymentCompleted 이벤트가 다시 수신되면") {
            Then("재고 처리 비즈니스 로직이 실행되지 않고 inbox_events에 중복 저장되지 않는다") {
                every { inboxEventRepository.saveIfAbsent(any()) } returns false

                shouldNotThrow<Exception> {
                    inventoryService.processPaymentCompleted(
                        messageId = "pay-001",
                        payload = validPayload,
                    )
                }
                verify(exactly = 0) { inventoryRepository.save(any()) }
            }
        }
    }

    Given("message_id = 'pay-001' 이 inbox에 없지만 재고 처리 중 오류가 발생하는 상황일 때") {
        When("이벤트를 수신하면") {
            Then("예외가 전파된다") {
                every { inboxEventRepository.saveIfAbsent(any()) } returns true
                every { inventoryRepository.save(any()) } throws RuntimeException("inventory failed")

                shouldThrow<RuntimeException> {
                    inventoryService.processPaymentCompleted(
                        messageId = "pay-001",
                        payload = validPayload,
                    )
                }
            }
        }
    }
})
