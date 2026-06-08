package com.example.shopbox.order.service

import com.example.shopbox.order.entity.Order
import com.example.shopbox.order.repository.OrderRepository
import com.example.shopbox.outbox.repository.OutboxEventRepository
import com.navercorp.fixturemonkey.FixtureMonkey
import com.navercorp.fixturemonkey.kotlin.KotlinPlugin
import com.navercorp.fixturemonkey.kotlin.giveMeKotlinBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.dao.DataIntegrityViolationException

class OrderServiceTest : BehaviorSpec({

    val fixtureMonkey = FixtureMonkey.builder()
        .plugin(KotlinPlugin())
        .build()

    val orderRepository = mockk<OrderRepository>()
    val outboxEventRepository = mockk<OutboxEventRepository>()
    val orderService = OrderService(
        orderRepository = orderRepository,
        outboxEventRepository = outboxEventRepository,
        objectMapper = com.fasterxml.jackson.databind.ObjectMapper(),
    )

    Given("DB가 정상 동작 중일 때") {
        When("사용자가 주문 생성을 요청하면") {
            val savedOrder = fixtureMonkey.giveMeKotlinBuilder<Order>()
                .set(Order::id, 1L)
                .set(Order::userId, 1L)
                .set(Order::productId, 100L)
                .set(Order::quantity, 2)
                .sample()

            every { orderRepository.save(any()) } returns savedOrder
            every { outboxEventRepository.save(any()) } answers { firstArg() }

            val result = orderService.createOrder(
                userId = 1L,
                productId = 100L,
                quantity = 2,
            )

            Then("주문이 저장된다") {
                result shouldNotBe null
                result.userId shouldBe 1L
                result.productId shouldBe 100L
                result.quantity shouldBe 2
            }

            Then("OutboxEvent가 저장된다") {
                verify(exactly = 1) { outboxEventRepository.save(any()) }
            }
        }
    }

    Given("outbox_events 저장에 오류가 발생하는 상황일 때") {
        When("사용자가 주문 생성을 요청하면") {
            every { orderRepository.save(any()) } returns fixtureMonkey.giveMeKotlinBuilder<Order>().sample()
            every { outboxEventRepository.save(any()) } throws DataIntegrityViolationException("outbox insert failed")

            Then("예외가 전파되어 트랜잭션이 롤백된다") {
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
})
