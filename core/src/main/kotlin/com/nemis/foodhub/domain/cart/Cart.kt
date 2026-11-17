package com.nemis.foodhub.domain.cart

import com.nemis.foodhub.domain.ValidationException
import com.nemis.foodhub.domain.model.CartItem
import com.nemis.foodhub.domain.model.MenuItem
import java.time.Clock
import java.time.Instant

/**
 * Narrow interface of the Memento (C6). The caretaker (history, draft repository) can store it and read
 * its timestamp, but cannot see or alter the cart's internals.
 */
interface CartSnapshot {
    val takenAt: Instant
}

/** Originator. A cart holds items of a single restaurant. */
class Cart(private val clock: Clock) {

    private val lines = LinkedHashMap<String, CartItem>()

    val items: List<CartItem> get() = lines.values.toList()
    val isEmpty: Boolean get() = lines.isEmpty()

    fun add(item: MenuItem, quantity: Int = 1) {
        if (quantity <= 0) throw ValidationException("Quantity must be positive")
        val restaurant = lines.values.firstOrNull()?.item?.restaurantId
        if (restaurant != null && restaurant != item.restaurantId) {
            throw ValidationException("Cart already contains items from another restaurant")
        }
        val current = lines[item.id]?.quantity ?: 0
        lines[item.id] = CartItem(item, current + quantity)
    }

    fun remove(itemId: String) {
        if (lines.remove(itemId) == null) throw ValidationException("Item $itemId is not in the cart")
    }

    fun setQuantity(itemId: String, quantity: Int) {
        val line = lines[itemId] ?: throw ValidationException("Item $itemId is not in the cart")
        if (quantity < 0) throw ValidationException("Quantity must not be negative")
        if (quantity == 0) lines.remove(itemId) else lines[itemId] = CartItem(line.item, quantity)
    }

    fun clear() = lines.clear()

    fun createSnapshot(): CartSnapshot = Memento(clock.instant(), LinkedHashMap(lines))

    fun restore(snapshot: CartSnapshot) {
        val memento = snapshot as? Memento
            ?: throw IllegalArgumentException("Snapshot was not created by a Cart")
        lines.clear()
        lines.putAll(memento.lines)
    }

    /** Private to Cart: nobody else can name this type or read [lines]. CartItem/MenuItem are immutable. */
    private class Memento(override val takenAt: Instant, val lines: Map<String, CartItem>) : CartSnapshot
}
