package com.example.shopbox.support.containers

import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.support.TestPropertySourceUtils

class TestContainersInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
            context,
            "spring.datasource.url=${TestContainers.mysql.jdbcUrl}",
            "spring.datasource.username=${TestContainers.mysql.username}",
            "spring.datasource.password=${TestContainers.mysql.password}",
            "spring.kafka.bootstrap-servers=${TestContainers.kafka.bootstrapServers}",
        )
    }
}
