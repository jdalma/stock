package org.test.stock

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class StockTestApplication

fun main(args: Array<String>) {
    runApplication<StockTestApplication>(*args)
}
