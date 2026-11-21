package lab.adcommerce.catalog

import lab.adcommerce.common.NotFound
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@SpringBootApplication(scanBasePackages = ["lab.adcommerce"])
class CatalogApplication

fun main(args: Array<String>) {
    runApplication<CatalogApplication>(*args)
}

/** 광고 상품. 금액은 원 단위 정수. */
data class Product(
    val id: Long,
    val code: String,
    val name: String,
    val placement: String,
    val cpvPrice: Long,
    val minBudget: Long,
    val maxDays: Int,
    val active: Boolean,
)

@RestController
class ProductController(private val jdbc: JdbcClient) {

    @GetMapping("/products")
    fun list(): List<Product> = jdbc.sql("SELECT * FROM products ORDER BY id").query(Product::class.java).list().filterNotNull()

    @GetMapping("/products/{id}")
    fun get(@PathVariable id: Long): Product =
        jdbc.sql("SELECT * FROM products WHERE id = :id").param("id", id).query(Product::class.java).optional()
            .orElseThrow { NotFound("상품 $id 이(가) 없습니다") }
}
