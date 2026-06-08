package com.example.shopbox.support.containers

import org.testcontainers.containers.MySQLContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

object TestContainers {
    val mysql: MySQLContainer<*> = MySQLContainer(DockerImageName.parse("mysql:8.0"))
        .apply { start() }

    val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse("apache/kafka:4.2.0"))
        .apply { start() }
}
