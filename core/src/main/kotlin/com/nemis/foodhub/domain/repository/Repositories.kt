package com.nemis.foodhub.domain.repository

import com.nemis.foodhub.domain.OrderNotFoundException
import com.nemis.foodhub.domain.model.CartItem
import com.nemis.foodhub.domain.model.MenuItem
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.order.Order

// Interfaces live in the domain; implementations live in the data layer (DIP).

interface OrderRepository {
    fun save(order: Order)
    fun findById(id: OrderId): Order?
}

fun OrderRepository.getOrThrow(id: OrderId): Order = findById(id) ?: throw OrderNotFoundException(id)

interface MenuRepository {
    fun restaurantIds(): List<String>
    fun findItem(itemId: String): MenuItem?
    fun itemsOf(restaurantId: String): List<MenuItem>

    /** Code of the region a restaurant operates in (selects the RegionFactory at checkout); null when unknown. */
    fun regionOf(restaurantId: String): String?
}

interface InventoryService {
    fun isAvailable(itemId: String, quantity: Int): Boolean

    /** All-or-nothing. Throws OutOfStockException and reserves nothing when any line is unavailable. */
    fun reserve(items: List<CartItem>)

    fun release(items: List<CartItem>)

    /**
     * All-or-nothing swap used when an order is edited: gives back [current] and takes [replacement].
     * Throws OutOfStockException and changes nothing when the replacement is not available.
     */
    fun replace(current: List<CartItem>, replacement: List<CartItem>)
}

fun interface IdGenerator {
    fun next(): String
}
