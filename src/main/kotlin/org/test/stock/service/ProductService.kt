package org.test.stock.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.test.stock.dto.*
import org.test.stock.entity.Product
import org.test.stock.repository.ProductRepository
import java.util.concurrent.TimeUnit

@Service
@Transactional(readOnly = true)
class ProductService(
    private val productRepository: ProductRepository,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(ProductService::class.java)

    private val deserializationTimer = Timer.builder("product.deserialization.time")
        .description("Time taken to deserialize product metadata JSON")
        .tag("operation", "json_deserialization")
        .register(meterRegistry)

    fun getProductById(id: Long): ProductResponse {
        logger.info("Fetching product with id: $id")
        val product = productRepository.findById(id)
            .orElseThrow { NoSuchElementException("Product not found with id: $id") }

        return convertToResponse(product)
    }

    fun getAllProducts(page: Int, size: Int): ProductListResponse {
        logger.info("Fetching products - page: $page, size: $size")
        val pageable: Pageable = PageRequest.of(page, size)
        val productPage = productRepository.findAll(pageable)

        val summaries = productPage.content.map { product ->
            val metadata = deserializeMetadata(product.metadataJson)
            ProductSummary(
                id = product.id!!,
                name = product.name,
                category = product.category,
                price = product.price,
                brand = product.brand,
                productCode = product.productCode,
                mainImageUrl = metadata.images.firstOrNull { it.type == "main" }?.url,
                averageRating = if (metadata.reviews.isNotEmpty()) {
                    metadata.reviews.map { it.rating }.average()
                } else null,
                reviewCount = metadata.reviews.size
            )
        }

        return ProductListResponse(
            products = summaries,
            totalCount = productPage.totalElements,
            page = page,
            size = size
        )
    }

    fun searchProductsByName(name: String, page: Int, size: Int): ProductListResponse {
        logger.info("Searching products with name: $name - page: $page, size: $size")
        val pageable: Pageable = PageRequest.of(page, size)
        val productPage = productRepository.findByNameContaining(name, pageable)

        val summaries = productPage.content.map { product ->
            val metadata = deserializeMetadata(product.metadataJson)
            ProductSummary(
                id = product.id!!,
                name = product.name,
                category = product.category,
                price = product.price,
                brand = product.brand,
                productCode = product.productCode,
                mainImageUrl = metadata.images.firstOrNull { it.type == "main" }?.url,
                averageRating = if (metadata.reviews.isNotEmpty()) {
                    metadata.reviews.map { it.rating }.average()
                } else null,
                reviewCount = metadata.reviews.size
            )
        }

        return ProductListResponse(
            products = summaries,
            totalCount = productPage.totalElements,
            page = page,
            size = size
        )
    }

    private fun convertToResponse(product: Product): ProductResponse {
        val metadata = deserializeMetadata(product.metadataJson)

        return ProductResponse(
            id = product.id!!,
            name = product.name,
            category = product.category,
            price = product.price,
            brand = product.brand,
            productCode = product.productCode,
            metadata = metadata,
            createdAt = product.createdAt,
            updatedAt = product.updatedAt
        )
    }

    private fun deserializeMetadata(json: String): ProductMetadata {
        val startTime = System.nanoTime()

        return try {
            val metadata = objectMapper.readValue(json, ProductMetadata::class.java)
            val duration = System.nanoTime() - startTime

            // Record the deserialization time
            deserializationTimer.record(duration, TimeUnit.NANOSECONDS)

            // Log if deserialization takes too long
            val durationMs = TimeUnit.NANOSECONDS.toMillis(duration)
            if (durationMs > 10) {
                logger.warn("Slow JSON deserialization detected: ${durationMs}ms for ${json.length} bytes")
            } else {
                logger.debug("JSON deserialized in ${durationMs}ms")
            }

            metadata
        } catch (e: Exception) {
            logger.error("Failed to deserialize product metadata", e)
            throw RuntimeException("Failed to deserialize product metadata", e)
        }
    }
}
