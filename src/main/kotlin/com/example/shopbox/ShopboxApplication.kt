package com.example.shopbox

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class ShopboxApplication

fun main(args: Array<String>) {
	runApplication<ShopboxApplication>(*args)
}
