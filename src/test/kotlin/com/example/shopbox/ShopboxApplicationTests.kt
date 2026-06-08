package com.example.shopbox

import com.example.shopbox.support.containers.TestContainersInitializer
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration

@SpringBootTest
@ContextConfiguration(initializers = [TestContainersInitializer::class])
class ShopboxApplicationTests {

    @Test
    fun contextLoads() {
    }
}
