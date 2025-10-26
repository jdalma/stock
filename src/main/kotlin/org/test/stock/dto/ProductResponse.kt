package org.test.stock.dto

import java.math.BigDecimal
import java.time.LocalDateTime

data class ProductResponse(
    val id: Long,
    val name: String,
    val category: String,
    val price: BigDecimal,
    val brand: String,
    val productCode: String,
    val metadata: ProductMetadata, // 역직렬화된 메타데이터
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class ProductListResponse(
    val products: List<ProductSummary>,
    val totalCount: Long,
    val page: Int,
    val size: Int
)

data class ProductSummary(
    val id: Long,
    val name: String,
    val category: String,
    val price: BigDecimal,
    val brand: String,
    val productCode: String,
    val mainImageUrl: String?,
    val averageRating: Double?,
    val reviewCount: Int
)
