package com.example.shopbox.inventory.service.strategy

import com.example.shopbox.inventory.entity.Stock
import com.example.shopbox.inventory.repository.StockRepository
import com.example.shopbox.support.containers.LockStrategyContainersInitializer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["inventory.lock-strategy=optimistic", "order.lock-strategy=optimistic"],
)
@ContextConfiguration(initializers = [LockStrategyContainersInitializer::class])
class InventoryOptimisticLockTest : BehaviorSpec() {

    @Autowired lateinit var inventoryLockStrategy: InventoryLockStrategy
    @Autowired lateinit var stockRepository: StockRepository

    init {
        extension(SpringExtension)

        beforeEach {
            stockRepository.deleteAllInBatch()
            stockRepository.saveAndFlush(Stock.create(productId = 1L, quantity = 100))
        }

        Given("재고 100개인 상품에 200개 스레드가 동시에 재고 차감을 요청할 때") {
            When("OptimisticLockStrategy로 처리하면") {
                Then("성공 건수는 최대 100이고 재고는 0 이상이다") {
                    val threadCount = 200
                    val startLatch = CountDownLatch(1)
                    val doneLatch = CountDownLatch(threadCount)
                    val successCount = AtomicInteger(0)
                    val executor = Executors.newFixedThreadPool(threadCount)

                    repeat(threadCount) {
                        executor.submit {
                            startLatch.await()
                            try {
                                inventoryLockStrategy.deductStock(
                                    productId = 1L,
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
                    doneLatch.await(60, TimeUnit.SECONDS)
                    executor.shutdown()

                    val stock = stockRepository.findByProductId(1L)
                    stock shouldNotBe null
                    (successCount.get() <= 100) shouldBe true
                    stock!!.quantity shouldBe (100 - successCount.get())
                    (stock.quantity >= 0) shouldBe true
                }
            }
        }
    }
}
