package com.example.shopbox.inventory.service

import com.example.shopbox.delivery.event.DeliveryFailedEvent
import com.example.shopbox.inbox.repository.InboxEventRepository
import com.example.shopbox.inventory.entity.Inventory
import com.example.shopbox.inventory.entity.enums.InventoryStatus
import com.example.shopbox.inventory.repository.InventoryRepository
import com.example.shopbox.inventory.service.strategy.InventoryLockStrategy
import com.example.shopbox.outbox.repository.OutboxEventRepository
import com.example.shopbox.payment.event.PaymentCompletedEvent
import com.navercorp.fixturemonkey.FixtureMonkey
import com.navercorp.fixturemonkey.kotlin.KotlinPlugin
import com.navercorp.fixturemonkey.kotlin.giveMeKotlinBuilder
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify

class InventoryServiceTest : BehaviorSpec({

    val fixtureMonkey = FixtureMonkey.builder()
        .plugin(KotlinPlugin())
        .build()

    val inventoryRepository = mockk<InventoryRepository>()
    val inboxEventRepository = mockk<InboxEventRepository>()
    val outboxEventRepository = mockk<OutboxEventRepository>()
    val inventoryLockStrategy = mockk<InventoryLockStrategy>()
    val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()

    val inventoryService = InventoryService(
        inventoryRepository = inventoryRepository,
        inboxEventRepository = inboxEventRepository,
        outboxEventRepository = outboxEventRepository,
        inventoryLockStrategy = inventoryLockStrategy,
        objectMapper = objectMapper,
    )

    val validPayload = """
        {
          "eventId": "pay-001",
          "eventType": "${PaymentCompletedEvent.EVENT_TYPE}",
          "occurredAt": "2026-06-06T13:00:00Z",
          "paymentId": 1,
          "orderId": 1,
          "amount": 0,
          "productId": 100,
          "quantity": 2
        }
    """.trimIndent()

    val stockReservationFailedPayload = """
        {
          "eventId": "fail-001",
          "eventType": "StockReservationFailed",
          "occurredAt": "2026-06-15T10:00:00Z",
          "reason": "재고 부족",
          "orderId": 1,
          "productId": 100,
          "quantity": 2
        }
    """.trimIndent()

    val deliveryFailedPayload = """
        {
          "eventId": "del-001",
          "eventType": "${DeliveryFailedEvent.EVENT_TYPE}",
          "occurredAt": "2026-06-15T10:00:00Z",
          "reason": "배송 실패",
          "deliveryId": 1,
          "orderId": 1
        }
    """.trimIndent()

    beforeEach { clearAllMocks() }

    Given("message_id = 'pay-001' 이 inbox에 없을 때") {
        When("PaymentCompleted 이벤트가 수신되면") {
            Then("재고 처리 비즈니스 로직이 실행되고 inbox_events에 기록된다") {
                every { inboxEventRepository.saveIfAbsent(any()) } returns true
                every { inventoryLockStrategy.deductStock(any(), any()) } just runs
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
                verify(exactly = 1) { inventoryLockStrategy.deductStock(any(), any()) }
                verify(exactly = 1) { inventoryRepository.save(any()) }
            }
        }
    }

    Given("message_id = 'pay-status-001' 이 inbox에 없을 때") {
        When("PaymentCompleted 이벤트가 수신되면") {
            Then("저장된 Inventory의 status가 RESERVED이다") {
                val slot = slot<Inventory>()
                every { inboxEventRepository.saveIfAbsent(any()) } returns true
                every { inventoryLockStrategy.deductStock(any(), any()) } just runs
                every { inventoryRepository.save(capture(slot)) } returns fixtureMonkey.giveMeKotlinBuilder<Inventory>()
                    .set(Inventory::id, 1L)
                    .sample()
                every { outboxEventRepository.save(any()) } answers { firstArg() }

                inventoryService.processPaymentCompleted(
                    messageId = "pay-status-001",
                    payload = validPayload,
                )

                slot.captured.status shouldBe InventoryStatus.RESERVED
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
                verify(exactly = 0) { inventoryLockStrategy.deductStock(any(), any()) }
            }
        }
    }

    Given("message_id = 'pay-001' 이 inbox에 없지만 재고 처리 중 오류가 발생하는 상황일 때") {
        When("이벤트를 수신하면") {
            Then("예외가 전파된다") {
                every { inboxEventRepository.saveIfAbsent(any()) } returns true
                every { inventoryLockStrategy.deductStock(any(), any()) } throws RuntimeException("inventory failed")

                shouldThrow<RuntimeException> {
                    inventoryService.processPaymentCompleted(
                        messageId = "pay-001",
                        payload = validPayload,
                    )
                }
                verify(exactly = 0) { inventoryRepository.save(any()) }
            }
        }
    }

    Given("message_id = 'fail-001' 이 inbox에 없을 때") {
        When("재고 예약 실패를 처리하면") {
            Then("Inventory가 FAILED로 저장되고 StockReservationFailed 이벤트가 발행된다") {
                val slot = slot<Inventory>()
                every { inboxEventRepository.saveIfAbsent(any()) } returns true
                every { inventoryRepository.save(capture(slot)) } returns fixtureMonkey.giveMeKotlinBuilder<Inventory>()
                    .set(Inventory::id, 1L)
                    .sample()
                every { outboxEventRepository.save(any()) } answers { firstArg() }

                shouldNotThrow<Exception> {
                    inventoryService.processStockReservationFailed(
                        messageId = "fail-001",
                        payload = stockReservationFailedPayload,
                    )
                }
                slot.captured.status shouldBe InventoryStatus.FAILED
                verify(exactly = 1) { outboxEventRepository.save(any()) }
            }
        }
    }

    Given("message_id = 'fail-001' 이 이미 inbox에 있을 때") {
        When("동일한 재고 예약 실패 이벤트가 다시 처리되면") {
            Then("중복 처리되지 않는다") {
                every { inboxEventRepository.saveIfAbsent(any()) } returns false

                shouldNotThrow<Exception> {
                    inventoryService.processStockReservationFailed(
                        messageId = "fail-001",
                        payload = stockReservationFailedPayload,
                    )
                }
                verify(exactly = 0) { inventoryRepository.save(any()) }
            }
        }
    }

    Given("DeliveryFailed 이벤트를 수신할 때") {
        When("Inventory가 존재하는 경우") {
            Then("Stock이 복구되고 Inventory가 RESTORED로 저장되며 StockRestored 이벤트가 발행된다") {
                val inventory = fixtureMonkey.giveMeKotlinBuilder<Inventory>()
                    .set(Inventory::id, 1L)
                    .set(Inventory::orderId, 1L)
                    .set(Inventory::productId, 100L)
                    .set(Inventory::quantity, 2)
                    .set(Inventory::status, InventoryStatus.RESERVED)
                    .sample()
                val slot = slot<Inventory>()
                every { inboxEventRepository.saveIfAbsent(any()) } returns true
                every { inventoryRepository.findByOrderId(1L) } returns inventory
                every { inventoryLockStrategy.restoreStock(any(), any()) } just runs
                every { inventoryRepository.save(capture(slot)) } returns inventory
                every { outboxEventRepository.save(any()) } answers { firstArg() }

                shouldNotThrow<Exception> {
                    inventoryService.processDeliveryFailed(
                        messageId = "del-001",
                        payload = deliveryFailedPayload,
                    )
                }
                slot.captured.status shouldBe InventoryStatus.RESTORED
                verify(exactly = 1) { inventoryLockStrategy.restoreStock(100L, 2) }
                verify(exactly = 1) { outboxEventRepository.save(any()) }
            }
        }
    }

    Given("DeliveryFailed 이벤트가 이미 처리된 경우") {
        When("동일한 이벤트가 다시 수신되면") {
            Then("중복 처리되지 않는다") {
                every { inboxEventRepository.saveIfAbsent(any()) } returns false

                shouldNotThrow<Exception> {
                    inventoryService.processDeliveryFailed(
                        messageId = "del-001",
                        payload = deliveryFailedPayload,
                    )
                }
                verify(exactly = 0) { inventoryRepository.findByOrderId(any()) }
            }
        }
    }
})
