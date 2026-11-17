package com.nemis.foodhub.domain.cart

import com.nemis.foodhub.domain.Subscription
import com.nemis.foodhub.domain.model.MenuItem
import java.util.concurrent.CopyOnWriteArrayList

/** Caretaker: a bounded undo stack. When full, the oldest backup is discarded. */
class CartHistory(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val stack = ArrayDeque<CartSnapshot>()

    val size: Int get() = stack.size

    fun push(snapshot: CartSnapshot) {
        if (stack.size == capacity) stack.removeFirst()
        stack.addLast(snapshot)
    }

    fun pop(): CartSnapshot? = stack.removeLastOrNull()

    fun clear() = stack.clear()
}

/** Persists the latest snapshot per customer, so the cart survives the app being killed. */
interface CartDraftRepository {
    fun save(customerId: String, snapshot: CartSnapshot)
    fun load(customerId: String): CartSnapshot?
    fun clear(customerId: String)
}

/**
 * Use-case style service used by the ViewModels: every successful mutation first stores a backup (for undo)
 * and then saves a draft. A failed mutation leaves history and draft untouched.
 * Several ViewModels share one editor (cart screen, checkout), so it tells its observers after every change.
 */
class CartEditor(
    val cart: Cart,
    private val history: CartHistory,
    private val drafts: CartDraftRepository,
    private val customerId: String
) {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val canUndo: Boolean get() = history.size > 0
    val hasDraft: Boolean get() = drafts.load(customerId) != null

    fun add(item: MenuItem, quantity: Int = 1) = mutate { cart.add(item, quantity) }
    fun remove(itemId: String) = mutate { cart.remove(itemId) }
    fun setQuantity(itemId: String, quantity: Int) = mutate { cart.setQuantity(itemId, quantity) }
    fun clear() = mutate { cart.clear() }

    /** Returns false when there is nothing to undo. */
    fun undo(): Boolean {
        val previous = history.pop() ?: return false
        cart.restore(previous)
        saveDraft()
        notifyChanged()
        return true
    }

    /** Restores the saved draft (undoable). Returns false when no draft exists. */
    fun restoreDraft(): Boolean {
        val draft = drafts.load(customerId) ?: return false
        history.push(cart.createSnapshot())
        cart.restore(draft)
        notifyChanged()
        return true
    }

    /** The cart became an order: start over with an empty cart, no undo history and no draft. */
    fun completeCheckout() {
        cart.clear()
        history.clear()
        drafts.clear(customerId)
        notifyChanged()
    }

    fun observe(listener: () -> Unit): Subscription {
        val registration: () -> Unit = { listener() }
        listeners.add(registration)
        return object : Subscription {
            override val isActive: Boolean get() = registration in listeners
            override fun cancel() {
                listeners.remove(registration)
            }
        }
    }

    private fun mutate(change: () -> Unit) {
        val backup = cart.createSnapshot()
        change()
        history.push(backup)
        saveDraft()
        notifyChanged()
    }

    /** An empty cart is not worth restoring, so it removes the draft instead of saving it. */
    private fun saveDraft() {
        if (cart.isEmpty) drafts.clear(customerId) else drafts.save(customerId, cart.createSnapshot())
    }

    private fun notifyChanged() = listeners.forEach { it() }
}
