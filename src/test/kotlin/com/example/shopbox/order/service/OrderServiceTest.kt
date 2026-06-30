package com.example.shopbox.order.service

import com.example.shopbox.delivery.event.DeliveryStartedEvent
import com.example.shopbox.inbox.repository.InboxEventRepository
import com.example.shopbox.order.entity.Order
import com.example.shopbox.order.entity.enums.OrderStatus
import com.example.shopbox.order.repository.OrderRepository
import com.example.shopbox.order.service.strategy.OrderLockStrategy
import com.example.shopbox.outbox.repository.OutboxEventRepository
import com.example.shopbox.payment.event.PaymentFailedEvent
import com.example.shopbox.payment.event.PaymentRefundedEvent
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
import org.springframework.dao.DataIntegrityViolationException
import java.util.Optional

class OrderServiceTest : BehaviorSpec({

    val fixtureMonkey = FixtureMonkey.builder()
        .plugin(KotlinPlugin())
        .build()

    val orderLockStrategy = mockk<OrderLockStrategy>()
    val orderRepository = mockk<OrderRepository>()
    val inboxEventRepository = mockk<InboxEventRepository>()
    val outboxEventRepository = mockk<OutboxEventRepository>()
    val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()

    val orderService = OrderService(
        orderLockStrategy = orderLockStrategy,
        orderRepository = orderRepository,
        inboxEventRepository = inboxEventRepository,
        outboxEventRepository = outboxEventRepository,
        objectMapper = objectMapper,
    )

    val deliveryStartedPayload = """
        {
          "eventId": "del-001",
          "eventType": "${DeliveryStartedEvent.EVENT_TYPE}",
          "occurredAt": "2026-06-15T10:00:00Z",
          "deliveryId": 1,
          "orderId": 1
        }
    """.trimIndent()

    val paymentFailedPayload = """
        {
          "eventId": "pay-fail-001",
          "eventType": "${PaymentFailedEvent.EVENT_TYPE}",
          "occurredAt": "2026-06-15T10:00:00Z",
          "reason": "결제 처리 중 오류",
          "paymentId": 1,
          "orderId": 1
        }
    """.trimIndent()

    val paymentRefundedPayload = """
        {
          "eventId": "pay-ref-001",
          "eventType": "${PaymentRefundedEvent.EVENT_TYPE}",
          "occurredAt": "2026-06-15T10:00:00Z",
          "reason": "재고 부족으로 인한 환불",
          "paymentId": 1,
          "orderId": 1
        }
    """.trimIndent()

    beforeEach { clearAllMocks() }

    Given("DB가 정상 동작 중일 때") {
        When("사용자가 주문 생성을 요청하면") {
            Then("주문이 생성되고 OutboxEvent가 저장된다") {
                val savedOrder = fixtureMonkey.giveMeKotlinBuilder<Order>()
                    .set(Order::id, 1L)
                    .set(Order::userId, 1L)
                    .set(Order::productId, 100L)
                    .set(Order::quantity, 2)
                    .sample()
                every { orderLockStrategy.createOrder(any(), any(), any()) } returns savedOrder
                every { outboxEventRepository.save(any()) } answers { firstArg() }

                val result = orderService.createOrder(
                    userId = 1L,
                    productId = 100L,
                    quantity = 2,
                )

                result shouldNotBe null
                result.userId shouldBe 1L
                result.productId shouldBe 100L
                result.quantity shouldBe 2
                verify(exactly = 1) { outboxEventRepository.save(any()) }
            }
        }
    }

    Given("outbox_events 저장에 오류가 발생하는 상황일 때") {
        When("사용자가 주문 생성을 요청하면") {
            Then("예외가 전파되어 트랜잭션이 롤백된다") {
                every { orderLockStrategy.createOrder(any(), any(), any()) } returns fixtureMonkey.giveMeKotlinBuilder<Order>()
                    .set(Order::id, 1L)
                    .sample()
                every { outboxEventRepository.save(any()) } throws DataIntegrityViolationException("outbox insert failed")

                shouldThrow<DataIntegrityViolationException> {
                    orderService.createOrder(
                        userId = 1L,
                        productId = 100L,
                        quantity = 2,
                    )
                }
            }
        }
    }

    Given("message_id = 'del-001' 이 inbox에 없을 때") {
        When("DeliveryStarted 이벤트가 수신되면") {
            Then("Order 상태가 CONFIRMED로 업데이트된다") {
                val order = fixtureMonkey.giveMeKotlinBuilder<Order>()
                    .set(Order::id, 1L)
                    .set(Order::status, OrderStatus.PENDING)
                    .sample()
                val slot = slot<Order>()
                every { inboxEventRepository.saveIfAbsent(any()) } returns true
                every { orderRepository.findById(1L) } returns Optional.of(order)
                every { orderRepository.save(capture(slot)) } returns order

                shouldNotThrow<Exception> {
                    orderService.processDeliveryStarted(
                        messageId = "del-001",
                        payload = deliveryStartedPayload,
                    )
                }
                slot.captured.status shouldBe OrderStatus.CONFIRMED
                verify(exactly = 0) { outboxEventRepository.save(any()) }
            }
        }
    }

    Given("message_id = 'del-001' 이 이미 inbox에 있을 때") {
        When("동일한 DeliveryStarted 이벤트가 다시 수신되면") {
            Then("중복 처리되지 않는다") {
                every { inboxEventRepository.saveIfAbsent(any()) } returns false

                shouldNotThrow<Exception> {
                    orderService.processDeliveryStarted(
                        messageId = "del-001",
                        payload = deliveryStartedPayload,
                    )
                }
                verify(exactly = 0) { orderRepository.save(any()) }
            }
        }
    }

    Given("message_id = 'pay-fail-001' 이 inbox에 없을 때") {
        When("PaymentFailed 이벤트를 수신하면") {
            Then("Order 상태가 CANCELLED로 업데이트되고 OrderCancelled 이벤트가 발행된다") {
                val order = fixtureMonkey.giveMeKotlinBuilder<Order>()
                    .set(Order::id, 1L)
                    .set(Order::status, OrderStatus.PENDING)
                    .sample()
                val slot = slot<Order>()
                every { inboxEventRepository.saveIfAbsent(any()) } returns true
                every { orderRepository.findById(1L) } returns Optional.of(order)
                every { orderRepository.save(capture(slot)) } returns order
                every { outboxEventRepository.save(any()) } answers { firstArg() }

                shouldNotThrow<Exception> {
                    orderService.processPaymentEvent(
                        messageId = "pay-fail-001",
                        payload = paymentFailedPayload,
                    )
                }
                slot.captured.status shouldBe OrderStatus.CANCELLED
                verify(exactly = 1) { outboxEventRepository.save(any()) }
            }
        }
    }

    Given("message_id = 'pay-ref-001' 이 inbox에 없을 때") {
        When("PaymentRefunded 이벤트를 수신하면") {
            Then("Order 상태가 CANCELLED로 업데이트되고 OrderCancelled 이벤트가 발행된다") {
                val order = fixtureMonkey.giveMeKotlinBuilder<Order>()
                    .set(Order::id, 1L)
                    .set(Order::status, OrderStatus.PENDING)
                    .sample()
                val slot = slot<Order>()
                every { inboxEventRepository.saveIfAbsent(any()) } returns true
                every { orderRepository.findById(1L) } returns Optional.of(order)
                every { orderRepository.save(capture(slot)) } returns order
                every { outboxEventRepository.save(any()) } answers { firstArg() }

                shouldNotThrow<Exception> {
                    orderService.processPaymentEvent(
                        messageId = "pay-ref-001",
                        payload = paymentRefundedPayload,
                    )
                }
                slot.captured.status shouldBe OrderStatus.CANCELLED
                verify(exactly = 1) { outboxEventRepository.save(any()) }
            }
        }
    }

    Given("message_id = 'pay-fail-001' 이 이미 inbox에 있을 때") {
        When("동일한 PaymentFailed 이벤트가 다시 수신되면") {
            Then("중복 처리되지 않는다") {
                every { inboxEventRepository.saveIfAbsent(any()) } returns false

                shouldNotThrow<Exception> {
                    orderService.processPaymentEvent(
                        messageId = "pay-fail-001",
                        payload = paymentFailedPayload,
                    )
                }
                verify(exactly = 0) { orderRepository.save(any()) }
            }
        }
    }
})
