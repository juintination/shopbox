package com.example.shopbox.order.controller

import com.example.shopbox.inbox.repository.InboxEventRepository
import com.example.shopbox.order.repository.OrderRepository
import com.example.shopbox.outbox.relay.MessageRelay
import com.example.shopbox.outbox.repository.OutboxEventRepository
import com.example.shopbox.payment.repository.PaymentRepository
import com.example.shopbox.support.containers.TestContainersInitializer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import java.util.concurrent.TimeUnit

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = [TestContainersInitializer::class])
class OrderControllerTest : BehaviorSpec() {

    @Autowired private lateinit var restTemplate: TestRestTemplate
    @Autowired private lateinit var orderRepository: OrderRepository
    @Autowired private lateinit var outboxEventRepository: OutboxEventRepository
    @Autowired private lateinit var inboxEventRepository: InboxEventRepository
    @Autowired private lateinit var paymentRepository: PaymentRepository
    @Autowired private lateinit var messageRelay: MessageRelay

    init {
        extension(SpringExtension)

        beforeEach {
            orderRepository.deleteAll()
            outboxEventRepository.deleteAll()
            inboxEventRepository.deleteAll()
            paymentRepository.deleteAll()
        }

        Given("DB가 정상 동작 중일 때") {
            When("POST /api/orders 로 주문을 생성하면") {
                Then("HTTP 201을 반환하고 orders, outbox_events 레코드가 생성된다") {
                    val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
                    val body = """{"userId": 1, "productId": 100, "quantity": 2}"""
                    val response = restTemplate.postForEntity(
                        "/api/orders",
                        HttpEntity(body, headers),
                        String::class.java,
                    )

                    response.statusCode shouldBe HttpStatus.CREATED
                    orderRepository.findAll().size shouldBe 1
                    val events = outboxEventRepository.findAll()
                    events.size shouldBe 1
                    events.first().processedAt shouldBe null
                }
            }
        }

        Given("주문 생성 후 릴레이가 실행되면") {
            When("relay() 가 order-events 토픽에 발행하면") {
                Then("PaymentService 가 소비하여 payments 가 생성된다") {
                    val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
                    val body = """{"userId": 1, "productId": 100, "quantity": 2}"""
                    restTemplate.postForEntity("/api/orders", HttpEntity(body, headers), String::class.java)

                    messageRelay.relay()

                    await().atMost(15, TimeUnit.SECONDS).until {
                        paymentRepository.findAll().isNotEmpty()
                    }

                    paymentRepository.findAll().size shouldBe 1
                }
            }
        }
    }
}
