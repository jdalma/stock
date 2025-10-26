package org.test.stock.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.test.stock.dto.*
import org.test.stock.entity.Product
import org.test.stock.repository.ProductRepository
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.random.Random

@Configuration
class DataInitializer {

    private val logger = LoggerFactory.getLogger(DataInitializer::class.java)

    @Bean
    fun initData(
        productRepository: ProductRepository,
        objectMapper: ObjectMapper
    ): CommandLineRunner {
        return CommandLineRunner {
            if (productRepository.count() == 0L) {
                logger.info("Initializing test data...")
                val startTime = System.currentTimeMillis()

                val products = generateProducts(100, objectMapper)
                productRepository.saveAll(products)

                val duration = System.currentTimeMillis() - startTime
                logger.info("Successfully created 100 products in ${duration}ms")
            } else {
                logger.info("Test data already exists. Skipping initialization.")
            }
        }
    }

    private fun generateProducts(count: Int, objectMapper: ObjectMapper): List<Product> {
        val categories = listOf("상의", "하의", "아우터", "원피스", "신발", "액세서리")
        val brands = listOf("ZARA", "H&M", "UNIQLO", "무신사스탠다드", "에잇세컨즈", "탑텐")
        val colors = listOf(
            ColorOption("블랙", "BLACK", "#000000", listOf("url1", "url2"), true, false),
            ColorOption("화이트", "WHITE", "#FFFFFF", listOf("url3", "url4"), true, false),
            ColorOption("네이비", "NAVY", "#000080", listOf("url5", "url6"), true, false),
            ColorOption("그레이", "GRAY", "#808080", listOf("url7", "url8"), true, false),
            ColorOption("베이지", "BEIGE", "#F5F5DC", listOf("url9", "url10"), true, true)
        )
        val sizes = listOf("XS", "S", "M", "L", "XL", "XXL")

        return (1..count).map { index ->
            val category = categories.random()
            val brand = brands.random()
            val productName = generateProductName(category, index)
            val metadata = generateProductMetadata(sizes, colors)

            Product(
                name = productName,
                category = category,
                price = BigDecimal(Random.nextInt(20000, 200000)),
                brand = brand,
                productCode = "PRD-${brand.take(3).uppercase()}-${String.format("%05d", index)}",
                metadataJson = objectMapper.writeValueAsString(metadata)
            )
        }
    }

    private fun generateProductName(category: String, index: Int): String {
        val adjectives = listOf("베이직", "프리미엄", "슬림핏", "오버핏", "빈티지", "모던")
        return "${adjectives.random()} ${category} ${index}"
    }

    private fun generateProductMetadata(sizes: List<String>, colors: List<ColorOption>): ProductMetadata {
        val reviewCount = Random.nextInt(10, 50)
        val imageCount = Random.nextInt(5, 10)

        return ProductMetadata(
            descriptions = mapOf(
                "ko" to generateDescription("ko"),
                "en" to generateDescription("en"),
                "zh" to generateDescription("zh"),
                "ja" to generateDescription("ja")
            ),
            detailedDescription = mapOf(
                "ko" to generateDetailedDescription("ko"),
                "en" to generateDetailedDescription("en"),
                "zh" to generateDetailedDescription("zh"),
                "ja" to generateDetailedDescription("ja")
            ),
            sizeOptions = sizes.map { size ->
                SizeOption(
                    size = size,
                    measurements = mapOf(
                        "chest" to Random.nextDouble(85.0, 110.0),
                        "waist" to Random.nextDouble(70.0, 95.0),
                        "hip" to Random.nextDouble(90.0, 115.0),
                        "length" to Random.nextDouble(60.0, 80.0)
                    ),
                    fitType = listOf("slim", "regular", "loose").random(),
                    available = Random.nextBoolean()
                )
            },
            colorOptions = colors,
            reviews = (1..reviewCount).map { generateReview(it, sizes, colors) },
            styleRecommendations = generateStyleRecommendations(),
            materialInfo = generateMaterialInfo(),
            careInstructions = mapOf(
                "ko" to listOf("찬물 손세탁", "표백제 사용 금지", "다림질 시 낮은 온도", "드라이클리닝 가능"),
                "en" to listOf("Hand wash in cold water", "Do not bleach", "Iron at low temperature", "Dry clean available"),
                "zh" to listOf("冷水手洗", "不可漂白", "低温熨烫", "可干洗"),
                "ja" to listOf("冷水で手洗い", "漂白剤使用不可", "低温でアイロン", "ドライクリーニング可")
            ),
            shippingPolicy = ShippingPolicy(
                domesticShipping = ShippingOption(true, BigDecimal(3000), "CJ대한통운", true),
                internationalShipping = ShippingOption(true, BigDecimal(15000), "EMS", true),
                freeShippingThreshold = BigDecimal(50000),
                estimatedDays = mapOf("서울" to 1, "경기" to 2, "지방" to 3)
            ),
            returnPolicy = ReturnPolicy(
                returnable = true,
                returnPeriodDays = 14,
                returnShippingCost = "Customer pays",
                conditions = listOf("상품 택 미제거", "착용 흔적 없음", "세탁하지 않은 상태"),
                exchangeAvailable = true
            ),
            brandInfo = generateBrandInfo(),
            categoryTags = listOf("캐주얼", "데일리", "스트리트", "미니멀", "베이직"),
            seasonTags = listOf("봄", "여름", "가을", "겨울").shuffled().take(2),
            images = (1..imageCount).map { index ->
                ImageMetadata(
                    imageId = "IMG-${Random.nextInt(10000, 99999)}",
                    url = "https://example.com/images/product_${index}.jpg",
                    type = if (index == 1) "main" else listOf("detail", "model", "flat").random(),
                    order = index,
                    altText = "Product image $index",
                    width = 1200,
                    height = 1600
                )
            },
            relatedProducts = (1..5).map { "PRD-${Random.nextInt(1000, 9999)}" },
            inventory = sizes.flatMap { size ->
                colors.map { color ->
                    val key = "$size-${color.colorName}"
                    val quantity = Random.nextInt(0, 100)
                    val reserved = if (quantity > 0) Random.nextInt(0, quantity) else 0
                    key to InventoryDetail(
                        quantity = quantity,
                        reservedQuantity = reserved,
                        availableQuantity = quantity - reserved,
                        lastRestockedAt = LocalDateTime.now().minusDays(Random.nextLong(1, 30)),
                        nextRestockDate = if (quantity < 10) LocalDateTime.now().plusDays(Random.nextLong(3, 14)) else null,
                        lowStockThreshold = 10
                    )
                }
            }.toMap()
        )
    }

    private fun generateDescription(lang: String): String {
        return when (lang) {
            "ko" -> "편안한 착용감과 세련된 디자인이 돋보이는 데일리 아이템입니다. 다양한 스타일링이 가능하며, 사계절 내내 착용 가능합니다. 고품질 소재를 사용하여 오랫동안 착용할 수 있습니다."
            "en" -> "A daily item featuring comfortable fit and sophisticated design. Versatile styling options available for all seasons. Made with high-quality materials for long-lasting wear."
            "zh" -> "舒适的穿着感和精致的设计,是日常必备单品。可以搭配多种风格,四季皆可穿着。采用高品质面料,经久耐用。"
            "ja" -> "快適な着心地と洗練されたデザインが魅力のデイリーアイテムです。様々なスタイリングが可能で、四季を通して着用できます。高品質な素材を使用し、長くご愛用いただけます。"
            else -> "Default description"
        }
    }

    private fun generateDetailedDescription(lang: String): String {
        return when (lang) {
            "ko" -> """
                [상품 특징]
                - 프리미엄 소재를 사용한 고급스러운 마감
                - 편안한 착용감을 위한 인체공학적 디자인
                - 세탁 후에도 형태가 유지되는 뛰어난 복원력
                - 다양한 체형에 잘 어울리는 유니버설 핏

                [스타일링 팁]
                - 캐주얼: 청바지와 스니커즈로 데일리룩 완성
                - 세미 포멀: 슬랙스와 로퍼로 깔끔한 오피스룩
                - 스트리트: 와이드 팬츠와 부츠로 힙한 스트리트룩

                [사이즈 가이드]
                모델 착용 사이즈: M / 모델 신장: 175cm, 체중: 65kg
                평소 사이즈대로 주문하시면 됩니다. 오버핏을 원하시면 한 사이즈 업을 추천합니다.
            """.trimIndent()
            "en" -> """
                [Product Features]
                - Luxurious finish using premium materials
                - Ergonomic design for comfortable wear
                - Excellent shape retention after washing
                - Universal fit suitable for various body types

                [Styling Tips]
                - Casual: Complete daily look with jeans and sneakers
                - Semi-formal: Clean office look with slacks and loafers
                - Street: Hip street look with wide pants and boots

                [Size Guide]
                Model wearing size: M / Model height: 175cm, weight: 65kg
                Order your usual size. Size up recommended for oversized fit.
            """.trimIndent()
            "zh" -> "详细的产品说明，包含面料特性、穿搭建议、尺码指南等信息。采用优质面料，舒适透气，适合日常穿着。"
            "ja" -> "商品の詳細説明です。素材の特徴、着こなしのアドバイス、サイズガイドなどの情報が含まれています。"
            else -> "Detailed description"
        }
    }

    private fun generateReview(index: Int, sizes: List<String>, colors: List<ColorOption>): Review {
        val ratings = listOf(4, 5, 5, 5, 4, 3, 5, 4, 5, 5) // Biased towards positive
        return Review(
            reviewId = "REV-${Random.nextInt(10000, 99999)}",
            rating = ratings.random(),
            title = listOf("정말 만족스러운 구매!", "기대 이상이에요", "추천합니다", "가성비 좋아요", "품질이 훌륭해요").random(),
            content = generateReviewContent(),
            author = ReviewAuthor(
                userId = "USER-${Random.nextInt(1000, 9999)}",
                nickname = "user${Random.nextInt(100, 999)}",
                height = Random.nextInt(155, 185),
                weight = Random.nextInt(45, 85),
                bodyType = listOf("마른", "보통", "통통").random(),
                reviewCount = Random.nextInt(1, 50)
            ),
            verifiedPurchase = Random.nextDouble() > 0.1, // 90% verified
            createdAt = LocalDateTime.now().minusDays(Random.nextLong(1, 365)),
            helpfulCount = Random.nextInt(0, 100),
            images = if (Random.nextBoolean()) (1..Random.nextInt(1, 4)).map { "review_img_$it.jpg" } else emptyList(),
            purchasedSize = sizes.random(),
            purchasedColor = colors.random().colorName,
            fitRating = listOf("작음", "딱 맞음", "큼").random(),
            qualityRating = Random.nextInt(3, 6)
        )
    }

    private fun generateReviewContent(): String {
        val templates = listOf(
            "색상도 예쁘고 핏도 좋아요. 재질도 생각보다 훨씬 좋네요. 다른 색상도 구매하려고 합니다.",
            "배송도 빠르고 상품도 만족스럽습니다. 사이즈는 평소 입던 사이즈로 주문했는데 딱 맞네요.",
            "가격 대비 퀄리티가 정말 좋습니다. 세탁 후에도 형태가 잘 유지되어 만족합니다.",
            "실물이 사진보다 더 예쁘네요. 입었을 때 핏이 정말 이쁩니다. 강추!",
            "처음에는 고민했는데 구매하길 잘했어요. 여러 옷과 잘 어울려서 자주 입게 됩니다."
        )
        return templates.random()
    }

    private fun generateStyleRecommendations(): List<StyleRecommendation> {
        return listOf(
            StyleRecommendation(
                style = "캐주얼 데일리",
                occasion = "일상, 데이트",
                season = listOf("봄", "가을"),
                matchingItems = listOf(
                    MatchingItem("하의", "PRD-001", "슬림핏 청바지", "url1"),
                    MatchingItem("신발", "PRD-002", "캔버스 스니커즈", "url2")
                ),
                description = "편안하면서도 세련된 일상 스타일"
            ),
            StyleRecommendation(
                style = "오피스 룩",
                occasion = "출근, 미팅",
                season = listOf("사계절"),
                matchingItems = listOf(
                    MatchingItem("하의", "PRD-003", "슬랙스", "url3"),
                    MatchingItem("신발", "PRD-004", "로퍼", "url4")
                ),
                description = "깔끔하고 단정한 직장인 스타일"
            )
        )
    }

    private fun generateMaterialInfo(): MaterialInfo {
        val compositions = listOf(
            mapOf("면" to 100),
            mapOf("면" to 80, "폴리에스터" to 20),
            mapOf("면" to 70, "폴리에스터" to 25, "스판덱스" to 5),
            mapOf("폴리에스터" to 100)
        )
        return MaterialInfo(
            composition = compositions.random(),
            origin = listOf("한국", "중국", "베트남", "방글라데시").random(),
            certifications = listOf("Oeko-Tex", "GOTS", "Fair Trade").shuffled().take(Random.nextInt(0, 2)),
            features = listOf("신축성 우수", "통기성 좋음", "속건성", "주름 방지").shuffled().take(Random.nextInt(1, 3)),
            weight = Random.nextDouble(150.0, 350.0),
            texture = listOf("부드러움", "매끄러움", "거친 느낌", "두께감").random()
        )
    }

    private fun generateBrandInfo(): BrandInfo {
        val brands = listOf(
            BrandInfo("BRD-001", "무신사스탠다드", "한국", 2001, "합리적인 가격의 베이직 패션 브랜드", 85, listOf("ISO9001")),
            BrandInfo("BRD-002", "에잇세컨즈", "한국", 2012, "모던하고 세련된 디자인의 SPA 브랜드", 78, listOf()),
            BrandInfo("BRD-003", "ZARA", "스페인", 1975, "글로벌 패스트 패션 리더", 72, listOf("SA8000")),
            BrandInfo("BRD-004", "UNIQLO", "일본", 1984, "고품질 베이직 캐주얼 브랜드", 90, listOf("ISO14001", "GOTS"))
        )
        return brands.random()
    }
}
