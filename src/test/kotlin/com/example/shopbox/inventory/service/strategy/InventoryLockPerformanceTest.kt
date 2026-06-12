package com.example.shopbox.inventory.service.strategy

import com.example.shopbox.inventory.entity.Stock
import com.example.shopbox.inventory.repository.StockRepository
import com.example.shopbox.support.containers.LockStrategyContainersInitializer
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
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
class InventoryLockPerformanceTest : BehaviorSpec() {

    @Autowired lateinit var stockRepository: StockRepository
    @Autowired lateinit var optimisticStrategy: InventoryOptimisticLockStrategy
    @Autowired lateinit var pessimisticStrategy: InventoryPessimisticLockStrategy
    @Autowired lateinit var distributedStrategy: InventoryDistributedLockStrategy

    private val log = KotlinLogging.logger {}

    init {
        extension(SpringExtension)

        Given("세 가지 락 전략의 성능을 비교할 때") {
            When("재고 100개 상품에 200 스레드가 동시에 차감을 요청하면") {
                Then("각 전략별 처리 시간·성공·실패 건수·TPS가 로그에 출력된다") {
                    val strategies: List<Pair<String, InventoryLockStrategy>> = listOf(
                        "Optimistic" to optimisticStrategy,
                        "Pessimistic" to pessimisticStrategy,
                        "Distributed" to distributedStrategy,
                    )

                    for ((name, strategy) in strategies) {
                        stockRepository.deleteAllInBatch()
                        stockRepository.saveAndFlush(Stock.create(productId = 1L, quantity = 100))

                        val threadCount = 200
                        val startLatch = CountDownLatch(1)
                        val doneLatch = CountDownLatch(threadCount)
                        val successCount = AtomicInteger(0)
                        val failCount = AtomicInteger(0)
                        val executor = Executors.newFixedThreadPool(threadCount)

                        repeat(threadCount) {
                            executor.submit {
                                startLatch.await()
                                try {
                                    strategy.deductStock(productId = 1L, quantity = 1)
                                    successCount.incrementAndGet()
                                } catch (e: Exception) {
                                    val count = failCount.incrementAndGet()
                                    if (count == 1) log.warn(e) { "[$name] first failure" }
                                } finally {
                                    doneLatch.countDown()
                                }
                            }
                        }

                        val start = System.currentTimeMillis()
                        startLatch.countDown()
                        doneLatch.await(120, TimeUnit.SECONDS)
                        val elapsed = System.currentTimeMillis() - start
                        executor.shutdown()

                        val tps = if (elapsed > 0) successCount.get() * 1000.0 / elapsed else 0.0
                        log.info {
                            "[$name] elapsed=${elapsed}ms success=${successCount.get()} fail=${failCount.get()} TPS=${"%.1f".format(tps)}"
                        }

                        val stock = stockRepository.findByProductId(1L)
                        stock shouldNotBe null
                    }
                }
            }
        }
    }
}
