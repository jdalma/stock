package org.test.stock

import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.slf4j.LoggerFactory

@SpringBootApplication
class StockTestApplication {

    private val logger = LoggerFactory.getLogger(StockTestApplication::class.java)

    @Value("\${my-first-database-host}")
    private lateinit var databaseHost: String

    @Value("\${my-first-database-password}")
    private lateinit var databasePassword: String

    @Bean
    fun checkParameterStore(): CommandLineRunner {
        return CommandLineRunner {
            logger.info("=".repeat(80))
            logger.info("ParameterStore 값 확인")
            logger.info("my-first-database-host: $databaseHost")
            logger.info("my-first-database-password: ${maskPassword(databasePassword)}")
            logger.info("=".repeat(80))
        }
    }

    private fun maskPassword(password: String): String {
        return if (password.length <= 4) {
            "*".repeat(password.length)
        } else {
            password.take(2) + "*".repeat(password.length - 4) + password.takeLast(2)
        }
    }
}

fun main(args: Array<String>) {
    runApplication<StockTestApplication>(*args)
}
