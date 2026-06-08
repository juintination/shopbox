package com.example.shopbox.payment.controller

import com.example.shopbox.inbox.repository.InboxEventRepository
import com.example.shopbox.payment.repository.PaymentRepository
import com.example.shopbox.support.containers.TestContainersInitializer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.ContextConfiguration
import java.util.concurrent.TimeUnit

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = [TestContainersInitializer::class])
class PaymentControllerTest : BehaviorSpec() {

    @Autowired private lateinit var kafkaTemplate: KafkaTemplate<String, String>
    @Autowired private lateinit var inboxEventRepository: InboxEventRepository
    @Autowired private lateinit var paymentRepository: PaymentRepository

    init {
        extension(SpringExtension)

        beforeEach {
            inboxEventRepository.deleteAll()
            paymentRepository.deleteAll()
        }

        Given("동일한 order-events 메시지가 2회 발행되면") {
            When("PaymentService 의 KafkaListener 가 중복 메시지를 수신하면") {
                Then("inbox_events 는 1건, payments 는 1건만 생성된다") {
                    val messageId = "idempotency-test-${System.currentTimeMillis()}"
                    val payload = """{"orderId": 99, "userId": 1, "productId": 100, "quantity": 2}"""

                    kafkaTemplate.send("order-events", messageId, payload).get()
                    kafkaTemplate.send("order-events", messageId, payload).get()

                    await().atMost(15, TimeUnit.SECONDS).until {
                        paymentRepository.findAll().isNotEmpty()
                    }
                    // 두 번째 메시지의 처리(idempotency 체크)도 완료될 시간 확보
                    Thread.sleep(1000)

                    inboxEventRepository.existsById(messageId) shouldBe true
                    paymentRepository.findAll().size shouldBe 1
                }
            }
        }
    }
}
