package com.nemis.foodhub.data

import com.nemis.foodhub.domain.OutOfStockException
import com.nemis.foodhub.domain.cart.CartDraftRepository
import com.nemis.foodhub.domain.cart.CartSnapshot
import com.nemis.foodhub.domain.model.CartItem
import com.nemis.foodhub.domain.model.Currency
import com.nemis.foodhub.domain.model.MenuItem
import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.order.Order
import com.nemis.foodhub.domain.pricing.PromoCatalog
import com.nemis.foodhub.domain.pricing.PromoCode
import com.nemis.foodhub.domain.repository.IdGenerator
import com.nemis.foodhub.domain.repository.InventoryService
import com.nemis.foodhub.domain.repository.MenuRepository
import com.nemis.foodhub.domain.repository.OrderRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Data layer: implements domain interfaces, depends on the domain, never the other way round.

class InMemoryOrderRepository : OrderRepository {
    private val orders = ConcurrentHashMap<OrderId, Order>()
    override fun save(order: Order) {
        orders[order.id] = order
    }

    override fun findById(id: OrderId): Order? = orders[id]
}

class InMemoryMenuRepository(items: List<MenuItem> = SAMPLE_MENU) : MenuRepository {
    private val byId = items.associateBy { it.id }

    override fun restaurantIds(): List<String> = byId.values.map { it.restaurantId }.distinct()
    override fun findItem(itemId: String): MenuItem? = byId[itemId]
    override fun itemsOf(restaurantId: String): List<MenuItem> = byId.values.filter { it.restaurantId == restaurantId }

    companion object {
        const val VN_RESTAURANT = "r-vn-pho"
        const val US_RESTAURANT = "r-us-burger"

        val SAMPLE_MENU = listOf(
            MenuItem("pho-bo", VN_RESTAURANT, "Pho bo", Money.of(55_000, Currency.VND)),
            MenuItem("ca-phe-sua", VN_RESTAURANT, "Ca phe sua da", Money.of(30_000, Currency.VND)),
            MenuItem("cheeseburger", US_RESTAURANT, "Cheeseburger", Money.of("8.99", Currency.USD)),
            MenuItem("fries", US_RESTAURANT, "Fries", Money.of("3.49", Currency.USD))
        )
    }
}

/** Thread-safe stock table. [reserve] is all-or-nothing. */
class SimulatedInventoryService(initialStock: Map<String, Int>) : InventoryService {
    private val stock = HashMap(initialStock)

    @Synchronized
    override fun isAvailable(itemId: String, quantity: Int) = (stock[itemId] ?: 0) >= quantity

    @Synchronized
    override fun reserve(items: List<CartItem>) {
        val needed = items.groupBy({ it.item.id }, { it.quantity }).mapValues { it.value.sum() }
        needed.forEach { (id, qty) -> if ((stock[id] ?: 0) < qty) throw OutOfStockException(id) }
        needed.forEach { (id, qty) -> stock[id] = stock.getValue(id) - qty }
    }

    @Synchronized
    override fun release(items: List<CartItem>) {
        items.forEach { stock[it.item.id] = (stock[it.item.id] ?: 0) + it.quantity }
    }

    @Synchronized
    fun stockOf(itemId: String): Int = stock[itemId] ?: 0
}

class InMemoryCartDraftRepository : CartDraftRepository {
    private val drafts = ConcurrentHashMap<String, CartSnapshot>()
    override fun save(customerId: String, snapshot: CartSnapshot) {
        drafts[customerId] = snapshot
    }

    override fun load(customerId: String): CartSnapshot? = drafts[customerId]
    override fun clear(customerId: String) {
        drafts.remove(customerId)
    }
}

class InMemoryPromoCatalog(promos: List<PromoCode> = listOf(PromoCode("SAVE10", 10), PromoCode("WELCOME20", 20))) :
    PromoCatalog {
    private val byCode = promos.associateBy { it.code }
    override fun find(code: String): PromoCode? = byCode[code.uppercase()]
}

class SequentialIdGenerator(private val prefix: String = "ORD") : IdGenerator {
    private val counter = AtomicInteger()
    override fun next(): String = "$prefix-%04d".format(counter.incrementAndGet())
}
