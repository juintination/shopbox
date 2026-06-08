package com.example.shopbox.payment.service

import com.example.shopbox.inbox.repository.InboxEventRepository
import com.example.shopbox.order.event.OrderCreatedEvent
import com.example.shopbox.outbox.repository.OutboxEventRepository
import com.example.shopbox.payment.entity.Payment
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
})
