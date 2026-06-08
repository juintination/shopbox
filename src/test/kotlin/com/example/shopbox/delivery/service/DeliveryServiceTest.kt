package com.example.shopbox.delivery.service

import com.example.shopbox.delivery.entity.Delivery
import com.example.shopbox.delivery.repository.DeliveryRepository
import com.example.shopbox.inbox.repository.InboxEventRepository
import com.example.shopbox.inventory.event.StockReservedEvent
import com.example.shopbox.outbox.repository.OutboxEventRepository
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

class DeliveryServiceTest : BehaviorSpec({

    val fixtureMonkey = FixtureMonkey.builder()
        .plugin(KotlinPlugin())
        .build()

    val deliveryRepository = mockk<DeliveryRepository>()
    val inboxEventRepository = mockk<InboxEventRepository>()
    val outboxEventRepository = mockk<OutboxEventRepository>()
    val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()

    val deliveryService = DeliveryService(
        deliveryRepository = deliveryRepository,
        inboxEventRepository = inboxEventRepository,
        outboxEventRepository = outboxEventRepository,
        objectMapper = objectMapper,
    )

    val validPayload = """
        {
          "eventId": "inv-001",
          "eventType": "${StockReservedEvent.EVENT_TYPE}",
          "occurredAt": "2026-06-06T13:00:00Z",
          "inventoryId": 1,
          "orderId": 1
        }
    """.trimIndent()

    beforeEach { clearAllMocks() }

    Given("message_id = 'inv-001' 이 inbox에 없을 때") {
        When("StockReserved 이벤트가 수신되면") {
            Then("배송 처리 비즈니스 로직이 실행되고 inbox_events에 기록된다") {
                every { inboxEventRepository.saveIfAbsent(any()) } returns true
                every { deliveryRepository.save(any()) } returns fixtureMonkey.giveMeKotlinBuilder<Delivery>()
                    .set(Delivery::id, 1L)
                    .sample()
                every { outboxEventRepository.save(any()) } answers { firstArg() }

                shouldNotThrow<Exception> {
                    deliveryService.processStockReserved(
                        messageId = "inv-001",
                        payload = validPayload,
                    )
                }
                verify(exactly = 1) { inboxEventRepository.saveIfAbsent(any()) }
                verify(exactly = 1) { deliveryRepository.save(any()) }
            }
        }
    }

    Given("message_id = 'inv-001' 이 이미 inbox에 있을 때") {
        When("동일한 StockReserved 이벤트가 다시 수신되면") {
            Then("배송 처리 비즈니스 로직이 실행되지 않고 inbox_events에 중복 저장되지 않는다") {
                every { inboxEventRepository.saveIfAbsent(any()) } returns false

                shouldNotThrow<Exception> {
                    deliveryService.processStockReserved(
                        messageId = "inv-001",
                        payload = validPayload,
                    )
                }
                verify(exactly = 0) { deliveryRepository.save(any()) }
            }
        }
    }

    Given("message_id = 'inv-001' 이 inbox에 없지만 배송 처리 중 오류가 발생하는 상황일 때") {
        When("이벤트를 수신하면") {
            Then("예외가 전파된다") {
                every { inboxEventRepository.saveIfAbsent(any()) } returns true
                every { deliveryRepository.save(any()) } throws RuntimeException("delivery failed")

                shouldThrow<RuntimeException> {
                    deliveryService.processStockReserved(
                        messageId = "inv-001",
                        payload = validPayload,
                    )
                }
            }
        }
    }
})
