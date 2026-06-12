package com.example.shopbox.order.service.strategy

import com.example.shopbox.order.repository.OrderRepository
import com.example.shopbox.support.containers.LockStrategyContainersInitializer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["order.lock-strategy=pessimistic", "inventory.lock-strategy=optimistic"],
)
@ContextConfiguration(initializers = [LockStrategyContainersInitializer::class])
class OrderPessimisticLockTest : BehaviorSpec() {

    @Autowired lateinit var orderLockStrategy: OrderLockStrategy
    @Autowired lateinit var orderRepository: OrderRepository

    init {
        extension(SpringExtension)

        beforeEach { orderRepository.deleteAllInBatch() }

        Given("동일 userId/productId로 50개 스레드가 동시에 주문 생성을 요청할 때") {
            When("PessimisticLockStrategy로 처리하면") {
                Then("정확히 1건만 성공하고 중복 주문은 생성되지 않는다") {
                    val threadCount = 50
                    val startLatch = CountDownLatch(1)
                    val doneLatch = CountDownLatch(threadCount)
                    val successCount = AtomicInteger(0)
                    val executor = Executors.newFixedThreadPool(threadCount)

                    repeat(threadCount) {
                        executor.submit {
                            startLatch.await()
                            try {
                                orderLockStrategy.createOrder(
                                    userId = 1L,
                                    productId = 100L,
                                    quantity = 1,
                                )
                                successCount.incrementAndGet()
                            } catch (_: Exception) {
                            } finally {
                                doneLatch.countDown()
                            }
                        }
                    }

                    startLatch.countDown()
                    doneLatch.await(30, TimeUnit.SECONDS)
                    executor.shutdown()

                    successCount.get() shouldBe 1
                    orderRepository.findAll().size shouldBe 1
                }
            }
        }
    }
}
