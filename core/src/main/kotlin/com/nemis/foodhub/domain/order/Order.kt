package com.nemis.foodhub.domain.order

import com.nemis.foodhub.domain.model.Address
import com.nemis.foodhub.domain.model.CartItem
import com.nemis.foodhub.domain.model.Customer
import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderStatus
import com.nemis.foodhub.domain.model.OrderType
import com.nemis.foodhub.domain.model.StatusTransition
import com.nemis.foodhub.domain.payment.PaymentMethod
import com.nemis.foodhub.domain.payment.PaymentReceipt
import com.nemis.foodhub.domain.pricing.PriceBreakdown
import com.nemis.foodhub.domain.pricing.PromoCode
import com.nemis.foodhub.domain.region.Region
import java.time.Clock
import java.time.Instant

/**
 * Aggregate root and State-pattern context. Every behavioural method delegates to the current state;
 * the order contains no branching on status.
 *
 * Concurrency: state changes are serialised on [lock]; events are published after the lock is released
 * so a slow or re-entrant listener can never deadlock the order.
 */
class Order(
    val id: OrderId,
    val customer: Customer,
    val restaurantId: String,
    val type: OrderType,
    val region: Region,
    val paymentMethod: PaymentMethod,
    val deliveryAddress: Address?,
    val scheduledAt: Instant?,
    val promo: PromoCode?,
    items: List<CartItem>,
    price: PriceBreakdown,
    val receipt: PaymentReceipt,
    private val clock: Clock,
    private val publisher: OrderEventPublisher
) {
    private val lock = Any()
    private val pending = ArrayList<OrderStatusChanged>()
    private val transitions = ArrayList<StatusTransition>()

    var items: List<CartItem> = items.toList()
        private set
    var price: PriceBreakdown = price
        private set
    var amountPaid: Money =
        if (receipt.collected) receipt.amount else Money.zero(receipt.amount.currency)
        private set
    var deliveredAt: Instant? = null
        private set
    var rating: Int? = null
        private set
    var refundRequested: Boolean = false
        private set

    private var state: OrderState = CreatedState

    val status: OrderStatus get() = synchronized(lock) { state.status }
    val capabilities: OrderCapabilities get() = synchronized(lock) { state.capabilitiesFor(context) }
    val history: List<StatusTransition> get() = synchronized(lock) { transitions.toList() }

    private val context = Context()

    fun confirm() = operate { state.confirm(context) }
    fun startPreparing() = operate { state.startPreparing(context) }
    fun startDelivery() = operate { state.startDelivery(context) }
    fun markDelivered() = operate { state.markDelivered(context) }
    fun cancel(): CancellationResult = operate { state.cancel(context) }
    fun editItems(newItems: List<CartItem>, newPrice: PriceBreakdown) =
        operate { state.editItems(context, newItems, newPrice) }

    fun requestRefund(): Money = operate { state.requestRefund(context) }
    fun rate(stars: Int) = operate { state.rate(context, stars) }

    private fun <T> operate(block: () -> T): T {
        val (result, events) = synchronized(lock) {
            val value = block()
            value to pending.toList().also { pending.clear() }
        }
        events.forEach(publisher::publish)
        return result
    }

    private inner class Context : OrderContext {
        override val type: OrderType get() = this@Order.type
        override val amountPaid: Money get() = this@Order.amountPaid
        override val paidOnline: Boolean get() = receipt.collected
        override val deliveredAt: Instant? get() = this@Order.deliveredAt
        override val refundRequested: Boolean get() = this@Order.refundRequested
        override val rated: Boolean get() = rating != null
        override val now: Instant get() = clock.instant()

        override fun transitionTo(next: OrderState) {
            val previous = state.status
            state = next
            val at = clock.instant()
            transitions.add(StatusTransition(previous, next.status, at))
            next.onEnter(this)
            pending.add(OrderStatusChanged(id, customer, previous, next.status, next.capabilitiesFor(this), at))
        }

        override fun replaceItems(items: List<CartItem>, price: PriceBreakdown) {
            this@Order.items = items.toList()
            this@Order.price = price
        }

        override fun recordDelivery() {
            this@Order.deliveredAt = clock.instant()
            // Cash on delivery: the money is collected by the courier / at the counter.
            if (!receipt.collected) this@Order.amountPaid = this@Order.price.total
        }

        override fun markRefundRequested() {
            this@Order.refundRequested = true
        }

        override fun recordRating(stars: Int) {
            this@Order.rating = stars
        }
    }
}
