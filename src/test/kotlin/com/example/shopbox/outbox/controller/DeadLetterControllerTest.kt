package com.example.shopbox.outbox.controller

import com.example.shopbox.outbox.entity.DeadLetterEvent
import com.example.shopbox.outbox.entity.OutboxEvent
import com.example.shopbox.outbox.repository.DeadLetterEventRepository
import com.example.shopbox.outbox.repository.OutboxEventRepository
import com.example.shopbox.support.containers.TestContainersInitializer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.ContextConfiguration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = [TestContainersInitializer::class])
class DeadLetterControllerTest : BehaviorSpec() {

    @Autowired private lateinit var restTemplate: TestRestTemplate
    @Autowired private lateinit var deadLetterEventRepository: DeadLetterEventRepository
    @Autowired private lateinit var outboxEventRepository: OutboxEventRepository

    init {
        extension(SpringExtension)

        beforeEach {
            deadLetterEventRepository.deleteAllInBatch()
            outboxEventRepository.deleteAllInBatch()
        }

        Given("dead_letter_events에 미재처리 이벤트가 있을 때") {
            When("GET /dead-letters를 호출하면") {
                Then("HTTP 200과 미재처리 이벤트 목록을 반환한다") {
                    deadLetterEventRepository.save(
                        DeadLetterEvent.create(
                            outboxEventId = 100L,
                            aggregateType = "Order",
                            aggregateId = 1L,
                            eventType = "OrderCreated",
                            payload = """{"orderId":1}""",
                            errorMessage = "Kafka timeout",
                        )
                    )

                    val response = restTemplate.getForEntity(
                        "/api/dead-letters",
                        String::class.java,
                    )

                    response.statusCode shouldBe HttpStatus.OK
                    response.body shouldNotBe null
                    response.body!!.contains("Order") shouldBe true
                }
            }
        }

        Given("dead_letter_events에 이벤트가 있을 때") {
            When("POST /dead-letters/{id}/retry를 호출하면") {
                Then("HTTP 200과 함께 outbox_events에 재등록되고 DLQ 이벤트가 Soft delete된다") {
                    val dlqEvent = deadLetterEventRepository.save(
                        DeadLetterEvent.create(
                            outboxEventId = 200L,
                            aggregateType = "Order",
                            aggregateId = 2L,
                            eventType = "OrderCreated",
                            payload = """{"orderId":2}""",
                            errorMessage = "Connection refused",
                        )
                    )

                    val response = restTemplate.postForEntity(
                        "/api/dead-letters/${dlqEvent.id}/retry",
                        null,
                        String::class.java,
                    )

                    response.statusCode shouldBe HttpStatus.OK
                    outboxEventRepository.findAll()
                        .any { it.aggregateType == "Order" && it.retryCount == 0 } shouldBe true
                }
            }
        }

        Given("존재하지 않는 DLQ id로 요청할 때") {
            When("POST /dead-letters/999999/retry를 호출하면") {
                Then("HTTP 400을 반환한다") {
                    val response = restTemplate.postForEntity(
                        "/api/dead-letters/999999/retry",
                        null,
                        String::class.java,
                    )

                    response.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }
        }

        Given("이미 재처리된(deleted_at IS NOT NULL) DLQ 이벤트가 있을 때") {
            When("GET /dead-letters를 호출하면") {
                Then("해당 이벤트는 목록에 포함되지 않는다") {
                    val dlqEvent = deadLetterEventRepository.save(
                        DeadLetterEvent.create(
                            outboxEventId = 300L,
                            aggregateType = "Order",
                            aggregateId = 3L,
                            eventType = "OrderCreated",
                            payload = """{"orderId":3}""",
                            errorMessage = "Timeout",
                        )
                    )
                    restTemplate.postForEntity(
                        "/api/dead-letters/${dlqEvent.id}/retry",
                        null,
                        String::class.java,
                    )

                    val response = restTemplate.getForEntity(
                        "/api/dead-letters",
                        String::class.java,
                    )

                    response.statusCode shouldBe HttpStatus.OK
                    response.body!!.contains(""""aggregateId":3""") shouldBe false
                }
            }
        }
    }
}
