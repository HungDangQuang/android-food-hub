package com.nemis.foodhub.domain.cart

import com.nemis.foodhub.domain.ValidationException
import com.nemis.foodhub.domain.model.CartItem
import com.nemis.foodhub.domain.model.Currency
import com.nemis.foodhub.domain.model.MenuItem
import com.nemis.foodhub.domain.model.Money
import java.math.BigDecimal
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.text.Charsets.UTF_8

/**
 * Narrow interface of the Memento (C6). The caretaker (history, draft repository) can store it and read
 * its timestamp, but cannot see or alter the cart's internals.
 */
interface CartSnapshot {
    val takenAt: Instant
}

/**
 * Turns a snapshot into text and back, so a draft can outlive the process. Only [Cart] implements it, because only
 * the originator may read a memento: the draft repository stores the text without understanding it.
 */
interface CartSnapshotCodec {
    fun encode(snapshot: CartSnapshot): String

    /** Throws IllegalArgumentException when [text] is not an encoded snapshot. */
    fun decode(text: String): CartSnapshot
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
        lines.clear()
        lines.putAll(memento(snapshot).lines)
    }

    /** Private to Cart: nobody else can name this type or read [lines]. CartItem/MenuItem are immutable. */
    private class Memento(override val takenAt: Instant, val lines: Map<String, CartItem>) : CartSnapshot

    /**
     * Stateless text form of a [Memento]: a header, the timestamp, then one line per cart line with URL-encoded
     * fields. It lives inside Cart, so the encoding stays as private as the memento itself.
     */
    companion object SnapshotCodec : CartSnapshotCodec {
        private const val HEADER = "foodhub-cart-v1"
        private const val FIELDS = 7

        override fun encode(snapshot: CartSnapshot): String {
            val memento = memento(snapshot)
            val rows = memento.lines.values.map { line ->
                val item = line.item
                listOf(
                    item.id, item.restaurantId, item.name, item.price.amount.toPlainString(),
                    item.price.currency.code, item.price.currency.fractionDigits.toString(), line.quantity.toString()
                ).joinToString(",") { URLEncoder.encode(it, UTF_8) }
            }
            return (listOf(HEADER, memento.takenAt.toString()) + rows).joinToString("\n")
        }

        override fun decode(text: String): CartSnapshot {
            val rows = text.lines()
            require(rows.size >= 2 && rows[0] == HEADER) { "Not an encoded cart snapshot" }
            val takenAt = try {
                Instant.parse(rows[1])
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("Bad snapshot timestamp", e)
            }
            val lines = LinkedHashMap<String, CartItem>()
            rows.drop(2).filter { it.isNotEmpty() }.forEach { row ->
                val f = row.split(',').map { URLDecoder.decode(it, UTF_8) }
                require(f.size == FIELDS) { "Malformed cart line" }
                val item = MenuItem(f[0], f[1], f[2], Money(BigDecimal(f[3]), Currency(f[4], f[5].toInt())))
                lines[item.id] = CartItem(item, f[6].toInt())
            }
            return Memento(takenAt, lines)
        }

        private fun memento(snapshot: CartSnapshot): Memento =
            snapshot as? Memento ?: throw IllegalArgumentException("Snapshot was not created by a Cart")
    }
}
