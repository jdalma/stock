package org.test.stock.controller

import io.micrometer.core.annotation.Timed
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.test.stock.dto.ProductListResponse
import org.test.stock.dto.ProductResponse
import org.test.stock.service.ProductService

@RestController
@RequestMapping("/api/products")
class ProductController(
    private val productService: ProductService
) {

    @GetMapping("/{id}")
    @Timed(value = "api.product.get.by.id", description = "Time taken to get product by ID")
    fun getProductById(@PathVariable id: Long): ResponseEntity<ProductResponse> {
        val product = productService.getProductById(id)
        return ResponseEntity.ok(product)
    }

    @GetMapping
    @Timed(value = "api.product.get.all", description = "Time taken to get all products")
    fun getAllProducts(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int
    ): ResponseEntity<ProductListResponse> {
        val products = productService.getAllProducts(page, size)
        return ResponseEntity.ok(products)
    }

    @GetMapping("/search")
    @Timed(value = "api.product.search", description = "Time taken to search products")
    fun searchProducts(
        @RequestParam name: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int
    ): ResponseEntity<ProductListResponse> {
        val products = productService.searchProductsByName(name, page, size)
        return ResponseEntity.ok(products)
    }
}
