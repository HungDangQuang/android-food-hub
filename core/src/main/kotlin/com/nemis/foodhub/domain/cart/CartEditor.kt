package com.nemis.foodhub.domain.cart

import com.nemis.foodhub.domain.model.MenuItem

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

/** Persists the latest snapshot per customer (e.g. so the cart survives the app being killed). */
interface CartDraftRepository {
    fun save(customerId: String, snapshot: CartSnapshot)
    fun load(customerId: String): CartSnapshot?
    fun clear(customerId: String)
}

/**
 * Use-case style service used by the ViewModel: every successful mutation first stores a backup (for undo)
 * and then saves a draft. A failed mutation leaves history and draft untouched.
 */
class CartEditor(
    val cart: Cart,
    private val history: CartHistory,
    private val drafts: CartDraftRepository,
    private val customerId: String
) {
    val canUndo: Boolean get() = history.size > 0

    fun add(item: MenuItem, quantity: Int = 1) = mutate { cart.add(item, quantity) }
    fun remove(itemId: String) = mutate { cart.remove(itemId) }
    fun setQuantity(itemId: String, quantity: Int) = mutate { cart.setQuantity(itemId, quantity) }
    fun clear() = mutate { cart.clear() }

    /** Returns false when there is nothing to undo. */
    fun undo(): Boolean {
        val previous = history.pop() ?: return false
        cart.restore(previous)
        drafts.save(customerId, cart.createSnapshot())
        return true
    }

    /** Restores the saved draft (undoable). Returns false when no draft exists. */
    fun restoreDraft(): Boolean {
        val draft = drafts.load(customerId) ?: return false
        history.push(cart.createSnapshot())
        cart.restore(draft)
        return true
    }

    private fun mutate(change: () -> Unit) {
        val backup = cart.createSnapshot()
        change()
        history.push(backup)
        drafts.save(customerId, cart.createSnapshot())
    }
}
