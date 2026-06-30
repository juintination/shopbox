package com.example.shopbox.payment.service

import com.example.shopbox.inbox.repository.InboxEventRepository
import com.example.shopbox.inventory.event.StockReservationFailedEvent
import com.example.shopbox.inventory.event.StockRestoredEvent
import com.example.shopbox.order.event.OrderCreatedEvent
import com.example.shopbox.outbox.repository.OutboxEventRepository
import com.example.shopbox.payment.entity.Payment
import com.example.shopbox.payment.entity.enums.PaymentStatus
import com.example.shopbox.payment.repository.PaymentRepository
import com.navercorp.fixturemonkey.FixtureMonkey
import com.navercorp.fixturemonkey.kotlin.KotlinPlugin
import com.navercorp.fixturemonkey.kotlin.giveMeKotlinBuilder
import io.kotest.assertions.throwables.shouldNotThrow
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

class PaymentServiceTest : BehaviorSpec({

    val fixtureMonkey = FixtureMonkey.builder()
        .plugin(KotlinPlugin())
        .build()

    val paymentRepository = mockk<PaymentRepository>()
    val inboxEventRepository = mockk<InboxEventRepository>()
    val outboxEventRepository = mockk<OutboxEventRepository>()
    val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()

    val paymentService = PaymentService(
        paymentRepository = paymentRepository,
        inboxEventRepository = inboxEventRepository,
        outboxEventRepository = outboxEventRepository,
        objectMapper = objectMapper,
    )

    val validPayload = """
        {
          "eventId": "msg-001",
          "eventType": "${OrderCreatedEvent.EVENT_TYPE}",
          "occurredAt": "2026-06-06T13:00:00Z",
          "orderId": 1,
          "userId": 1,
          "productId": 100,
          "quantity": 2
        }
    """.trimIndent()

    val paymentFailedPayload = """
        {
          "eventId": "pay-fail-001",
          "eventType": "PaymentFailed",
          "occurredAt": "2026-06-15T10:00:00Z",
          "reason": "결제 처리 중 오류",
          "paymentId": 1,
          "orderId": 1
        }
    """.trimIndent()

    val stockReservationFailedPayload = """
        {
          "eventId": "inv-fail-001",
          "eventType": "${StockReservationFailedEvent.EVENT_TYPE}",
          "occurredAt": "2026-06-15T10:00:00Z",
          "reason": "재고 부족",
          "orderId": 1,
          "productId": 100,
          "quantity": 2
        }
    """.trimIndent()

    val stockRestoredPayload = """
        {
          "eventId": "inv-res-001",
          "eventType": "${StockRestoredEvent.EVENT_TYPE}",
          "occurredAt": "2026-06-15T10:00:00Z",
          "reason": "배송 실패로 인한 재고 복구",
          "inventoryId": 1,
          "orderId": 1,
          "productId": 100,
          "quantity": 2
        }
    """.trimIndent()

    beforeEach { clearAllMocks() }

    Given("id = 1 인 결제가 DB에 존재할 때") {
        When("getPayment(1)을 호출하면") {
            Then("해당 Payment를 반환한다") {
                val payment = fixtureMonkey.giveMeKotlinBuilder<Payment>()
                    .set(Payment::id, 1L)
                    .sample()
                every { paymentRepository.findById(1L) } returns Optional.of(payment)

                val result = paymentService.getPayment(1L)

                result shouldNotBe null
                result!!.id shouldBe 1L
            }
        }
    }

    Given("id = 999 인 결제가 DB에 없을 때") {
        When("getPayment(999)을 호출하면") {
            Then("null을 반환한다") {
                every { paymentRepository.findById(999L) } returns Optional.empty()

                val result = paymentService.getPayment(999L)

                result shouldBe null
            }
        }
    }

    Given("message_id = 'msg-001' 이 inbox에 없을 때") {
        When("OrderCreated 이벤트가 수신되면") {
            Then("결제 처리 비즈니스 로직이 실행되고 inbox_events에 기록된다") {
                every { inboxEventRepository.saveIfAbsent(any()) } returns true
                every { paymentRepository.save(any()) } returns fixtureMonkey.giveMeKotlinBuilder<Payment>()
                    .set(Payment::id, 1L)
                    .sample()
                every { outboxEventRepository.save(any()) } answers { firstArg() }

                shouldNotThrow<Exception> {
                    paymentService.processOrderCreated(
                        messageId = "msg-001",
                        payload = validPayload,
                    )
                }
                verify(exactly = 1) { inboxEventRepository.saveIfAbsent(any()) }
                verify(exactly = 1) { paymentRepository.save(any()) }
            }
        }
    }

    Given("message_id = 'msg-status-001' 이 inbox에 없을 때") {
        When("OrderCreated 이벤트가 수신되면") {
            Then("저장된 Payment의 status가 COMPLETED이다") {
                val slot = slot<Payment>()
                every { inboxEventRepository.saveIfAbsent(any()) } returns true
                every { paymentRepository.save(capture(slot)) } returns fixtureMonkey.giveMeKotlinBuilder<Payment>()
                    .set(Payment::id, 1L)
                    .sample()
                every { outboxEventRepository.save(any()) } answers { firstArg() }

                paymentService.processOrderCreated(
                    messageId = "msg-status-001",
                    payload = validPayload,
                )

                slot.captured.status shouldBe PaymentStatus.COMPLETED
            }
        }
    }

    Given("message_id = 'msg-001' 이 이미 inbox에 있을 때") {
        When("동일한 OrderCreated 이벤트가 다시 수신되면") {
            Then("결제 처리 비즈니스 로직이 실행되지 않고 inbox_events에 중복 저장되지 않는다") {
                every { inboxEventRepository.saveIfAbsent(any()) } returns false

                shouldNotThrow<Exception> {
                    paymentService.processOrderCreated(
                        messageId = "msg-001",
                        payload = validPayload,
                    )
                }
                verify(exactly = 0) { paymentRepository.save(any()) }
            }
        }
    }

    Given("message_id = 'msg-001' 이 inbox에 없지만 결제 처리 중 오류가 발생하는 상황일 때") {
        When("이벤트를 수신하면") {
            Then("예외가 전파되어 inbox_events에 기록되지 않는다") {
                every { inboxEventRepository.saveIfAbsent(any()) } returns true
                every { paymentRepository.save(any()) } throws RuntimeException("payment failed")

                shouldThrow<RuntimeException> {
                    paymentService.processOrderCreated(
                        messageId = "msg-001",
                        payload = validPayload,
                    )
                }
            }
        }
    }

    Given("message_id = 'pay-fail-001' 이 inbox에 없을 때") {
        When("결제 실패를 처리하면") {
            Then("Payment가 FAILED로 저장되고 PaymentFailed 이벤트가 발행된다") {
                val slot = slot<Payment>()
                every { inboxEventRepository.saveIfAbsent(any()) } returns true
                every { paymentRepository.save(capture(slot)) } returns fixtureMonkey.giveMeKotlinBuilder<Payment>()
                    .set(Payment::id, 1L)
                    .sample()
                every { outboxEventRepository.save(any()) } answers { firstArg() }

                shouldNotThrow<Exception> {
                    paymentService.processPaymentFailed(
                        messageId = "pay-fail-001",
                        payload = paymentFailedPayload,
                    )
                }
                slot.captured.status shouldBe PaymentStatus.FAILED
                verify(exactly = 1) { outboxEventRepository.save(any()) }
            }
        }
    }

    Given("message_id = 'pay-fail-001' 이 이미 inbox에 있을 때") {
        When("동일한 결제 실패 이벤트가 다시 처리되면") {
            Then("중복 처리되지 않는다") {
                every { inboxEventRepository.saveIfAbsent(any()) } returns false

                shouldNotThrow<Exception> {
                    paymentService.processPaymentFailed(
                        messageId = "pay-fail-001",
                        payload = paymentFailedPayload,
                    )
                }
                verify(exactly = 0) { paymentRepository.save(any()) }
            }
        }
    }

    Given("StockReservationFailed 이벤트를 수신할 때") {
        When("Payment가 존재하는 경우") {
            Then("Payment가 REFUNDED로 저장되고 PaymentRefunded 이벤트가 발행된다") {
                val payment = fixtureMonkey.giveMeKotlinBuilder<Payment>()
                    .set(Payment::id, 1L)
                    .set(Payment::orderId, 1L)
                    .set(Payment::status, PaymentStatus.COMPLETED)
                    .sample()
                val slot = slot<Payment>()
                every { inboxEventRepository.saveIfAbsent(any()) } returns true
                every { paymentRepository.findByOrderId(1L) } returns payment
                every { paymentRepository.save(capture(slot)) } returns payment
                every { outboxEventRepository.save(any()) } answers { firstArg() }

                shouldNotThrow<Exception> {
                    paymentService.processInventoryEvent(
                        messageId = "inv-fail-001",
                        payload = stockReservationFailedPayload,
                    )
                }
                slot.captured.status shouldBe PaymentStatus.REFUNDED
                verify(exactly = 1) { outboxEventRepository.save(any()) }
            }
        }
    }

    Given("StockRestored 이벤트를 수신할 때") {
        When("Payment가 존재하는 경우") {
            Then("Payment가 REFUNDED로 저장되고 PaymentRefunded 이벤트가 발행된다") {
                val payment = fixtureMonkey.giveMeKotlinBuilder<Payment>()
                    .set(Payment::id, 1L)
                    .set(Payment::orderId, 1L)
                    .set(Payment::status, PaymentStatus.COMPLETED)
                    .sample()
                val slot = slot<Payment>()
                every { inboxEventRepository.saveIfAbsent(any()) } returns true
                every { paymentRepository.findByOrderId(1L) } returns payment
                every { paymentRepository.save(capture(slot)) } returns payment
                every { outboxEventRepository.save(any()) } answers { firstArg() }

                shouldNotThrow<Exception> {
                    paymentService.processInventoryEvent(
                        messageId = "inv-res-001",
                        payload = stockRestoredPayload,
                    )
                }
                slot.captured.status shouldBe PaymentStatus.REFUNDED
                verify(exactly = 1) { outboxEventRepository.save(any()) }
            }
        }
    }

    Given("inventory 이벤트를 수신할 때 이미 inbox에 있는 경우") {
        When("동일한 이벤트가 다시 처리되면") {
            Then("중복 처리되지 않는다") {
                every { inboxEventRepository.saveIfAbsent(any()) } returns false

                shouldNotThrow<Exception> {
                    paymentService.processInventoryEvent(
                        messageId = "inv-fail-001",
                        payload = stockReservationFailedPayload,
                    )
                }
                verify(exactly = 0) { paymentRepository.findByOrderId(any()) }
            }
        }
    }
})
