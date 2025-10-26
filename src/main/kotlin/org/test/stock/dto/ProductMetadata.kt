package org.test.stock.dto

import java.math.BigDecimal
import java.time.LocalDateTime

data class ProductMetadata(
    val descriptions: Map<String, String>, // 다국어 설명 (ko, en, zh, ja)
    val detailedDescription: Map<String, String>, // 상세 설명
    val sizeOptions: List<SizeOption>,
    val colorOptions: List<ColorOption>,
    val reviews: List<Review>,
    val styleRecommendations: List<StyleRecommendation>,
    val materialInfo: MaterialInfo,
    val careInstructions: Map<String, List<String>>, // 다국어 세탁 방법
    val shippingPolicy: ShippingPolicy,
    val returnPolicy: ReturnPolicy,
    val brandInfo: BrandInfo,
    val categoryTags: List<String>,
    val seasonTags: List<String>,
    val images: List<ImageMetadata>,
    val relatedProducts: List<String>, // 관련 상품 ID 리스트
    val inventory: Map<String, InventoryDetail> // "SIZE-COLOR" -> 재고 정보
)

data class SizeOption(
    val size: String,
    val measurements: Map<String, Double>, // chest, waist, hip, length 등
    val fitType: String, // slim, regular, loose
    val available: Boolean
)

data class ColorOption(
    val colorName: String,
    val colorCode: String,
    val hexCode: String,
    val imageUrls: List<String>,
    val available: Boolean,
    val premium: Boolean // 프리미엄 컬러 여부
)

data class Review(
    val reviewId: String,
    val rating: Int,
    val title: String,
    val content: String,
    val author: ReviewAuthor,
    val verifiedPurchase: Boolean,
    val createdAt: LocalDateTime,
    val helpfulCount: Int,
    val images: List<String>,
    val purchasedSize: String,
    val purchasedColor: String,
    val fitRating: String, // "작음", "딱 맞음", "큼"
    val qualityRating: Int
)

data class ReviewAuthor(
    val userId: String,
    val nickname: String,
    val height: Int?,
    val weight: Int?,
    val bodyType: String?,
    val reviewCount: Int
)

data class StyleRecommendation(
    val style: String,
    val occasion: String,
    val season: List<String>,
    val matchingItems: List<MatchingItem>,
    val description: String
)

data class MatchingItem(
    val category: String,
    val productId: String?,
    val description: String,
    val imageUrl: String?
)

data class MaterialInfo(
    val composition: Map<String, Int>, // "Cotton" -> 80, "Polyester" -> 20
    val origin: String,
    val certifications: List<String>, // 친환경 인증 등
    val features: List<String>, // 신축성, 통기성 등
    val weight: Double, // 원단 무게
    val texture: String
)

data class ShippingPolicy(
    val domesticShipping: ShippingOption,
    val internationalShipping: ShippingOption?,
    val freeShippingThreshold: BigDecimal?,
    val estimatedDays: Map<String, Int> // 지역별 배송 일수
)

data class ShippingOption(
    val available: Boolean,
    val cost: BigDecimal,
    val carrier: String,
    val trackingAvailable: Boolean
)

data class ReturnPolicy(
    val returnable: Boolean,
    val returnPeriodDays: Int,
    val returnShippingCost: String, // "Free", "Customer pays", "Seller pays conditionally"
    val conditions: List<String>,
    val exchangeAvailable: Boolean
)

data class BrandInfo(
    val brandId: String,
    val brandName: String,
    val country: String,
    val foundedYear: Int,
    val description: String,
    val sustainabilityScore: Int?, // 1-100
    val certifications: List<String>
)

data class ImageMetadata(
    val imageId: String,
    val url: String,
    val type: String, // "main", "detail", "model", "flat"
    val order: Int,
    val altText: String,
    val width: Int,
    val height: Int
)

data class InventoryDetail(
    val quantity: Int,
    val reservedQuantity: Int,
    val availableQuantity: Int,
    val lastRestockedAt: LocalDateTime?,
    val nextRestockDate: LocalDateTime?,
    val lowStockThreshold: Int
)
