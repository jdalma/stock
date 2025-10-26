package org.test.stock.repository

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.test.stock.entity.Product

@Repository
interface ProductRepository : JpaRepository<Product, Long> {
    fun findByNameContaining(name: String, pageable: Pageable): Page<Product>
    fun findByCategory(category: String, pageable: Pageable): Page<Product>
    fun findByBrand(brand: String, pageable: Pageable): Page<Product>
}
