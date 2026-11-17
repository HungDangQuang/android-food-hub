package com.nemis.foodhub.domain.order

import com.nemis.foodhub.domain.IllegalOrderOperationException
import com.nemis.foodhub.domain.RefundWindowExpiredException
import com.nemis.foodhub.domain.ValidationException
import com.nemis.foodhub.domain.model.CartItem
import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.model.OrderStatus
import com.nemis.foodhub.domain.model.OrderType
import com.nemis.foodhub.domain.pricing.PriceBreakdown
import java.time.Duration
import java.time.Instant

const val CANCELLATION_FEE_PERCENT_AFTER_CONFIRM = 10
val REFUND_WINDOW: Duration = Duration.ofHours(24)

data class CancellationResult(val fee: Money, val refund: Money)

/** What the UI may offer in a state. The rules live here only, so ViewModels never re-implement them. */
data class OrderCapabilities(
    val canEditItems: Boolean = false,
    val canCancel: Boolean = false,
    val cancellationFeePercent: Int = 0,
    val canRequestRefund: Boolean = false,
    val canRate: Boolean = false,
    val canTrackCourier: Boolean = false
) {
    companion object {
        val NONE = OrderCapabilities()
    }
}

/** Narrow view of the order handed to states (ISP/DIP): states cannot reach anything else on Order. */
interface OrderContext {
    val type: OrderType
    val amountPaid: Money

    /** True when the money was taken online at checkout (card, wallet); false for cash on delivery. */
    val paidOnline: Boolean
    val deliveredAt: Instant?
    val refundRequested: Boolean
    val rated: Boolean
    val now: Instant
    fun transitionTo(next: OrderState)
    fun replaceItems(items: List<CartItem>, price: PriceBreakdown)
    fun recordDelivery()
    fun markRefundRequested()
    fun recordRating(stars: Int)
}

/**
 * State pattern (C1). Every operation is rejected by default; a state overrides only what it allows.
 * LSP contract for all states: an operation either performs its effect or throws
 * [IllegalOrderOperationException]; it never silently does nothing.
 */
abstract class OrderState(val status: OrderStatus) {

    /** Static part of the rules of this state. */
    open val capabilities: OrderCapabilities = OrderCapabilities.NONE

    /**
     * What the UI may offer right now: [capabilities] refined by facts of this particular order
     * (e.g. a refund that was already requested is no longer offered). The UI never recomputes this.
     */
    open fun capabilitiesFor(ctx: OrderContext): OrderCapabilities = capabilities

    open fun onEnter(ctx: OrderContext) {}

    open fun confirm(ctx: OrderContext): Unit = reject("confirm")
    open fun startPreparing(ctx: OrderContext): Unit = reject("startPreparing")
    open fun startDelivery(ctx: OrderContext): Unit = reject("startDelivery")
    open fun markDelivered(ctx: OrderContext): Unit = reject("markDelivered")
    open fun cancel(ctx: OrderContext): CancellationResult = reject("cancel")
    open fun editItems(ctx: OrderContext, items: List<CartItem>, price: PriceBreakdown): Unit = reject("editItems")
    open fun requestRefund(ctx: OrderContext): Money = reject("requestRefund")
    open fun rate(ctx: OrderContext, stars: Int): Unit = reject("rate")

    protected fun reject(operation: String, reason: String? = null): Nothing =
        throw IllegalOrderOperationException(status, operation, reason)

    override fun toString() = status.name
}

// The concrete states are stateless, so a single instance each is enough (flyweight, not a service locator).

object CreatedState : OrderState(OrderStatus.CREATED) {
    override val capabilities = OrderCapabilities(canEditItems = true, canCancel = true)

    override fun confirm(ctx: OrderContext) = ctx.transitionTo(ConfirmedState)

    override fun cancel(ctx: OrderContext): CancellationResult {
        val result = CancellationResult(fee = Money.zero(ctx.amountPaid.currency), refund = ctx.amountPaid)
        ctx.transitionTo(CancelledState)
        return result
    }

    override fun editItems(ctx: OrderContext, items: List<CartItem>, price: PriceBreakdown) =
        ctx.replaceItems(items, price)
}

object ConfirmedState : OrderState(OrderStatus.CONFIRMED) {
    override val capabilities = OrderCapabilities(
        canCancel = true,
        cancellationFeePercent = CANCELLATION_FEE_PERCENT_AFTER_CONFIRM
    )

    override fun startPreparing(ctx: OrderContext) = ctx.transitionTo(PreparingState)

    override fun cancel(ctx: OrderContext): CancellationResult {
        val fee = ctx.amountPaid.percent(CANCELLATION_FEE_PERCENT_AFTER_CONFIRM)
        val result = CancellationResult(fee = fee, refund = ctx.amountPaid - fee)
        ctx.transitionTo(CancelledState)
        return result
    }
}

object PreparingState : OrderState(OrderStatus.PREPARING) {
    override fun startDelivery(ctx: OrderContext) {
        if (!ctx.type.requiresCourier) reject("startDelivery", "pickup orders have no courier")
        ctx.transitionTo(DeliveringState)
    }

    override fun markDelivered(ctx: OrderContext) {
        if (ctx.type.requiresCourier) reject("markDelivered", "courier must start delivery first")
        ctx.transitionTo(DeliveredState)
    }
}

object DeliveringState : OrderState(OrderStatus.DELIVERING) {
    override val capabilities = OrderCapabilities(canTrackCourier = true)

    override fun markDelivered(ctx: OrderContext) = ctx.transitionTo(DeliveredState)
}

object DeliveredState : OrderState(OrderStatus.DELIVERED) {
    override val capabilities = OrderCapabilities(canRequestRefund = true, canRate = true)

    override fun capabilitiesFor(ctx: OrderContext) = capabilities.copy(
        canRequestRefund = ctx.paidOnline && !ctx.refundRequested && withinRefundWindow(ctx),
        canRate = !ctx.rated
    )

    override fun onEnter(ctx: OrderContext) = ctx.recordDelivery()

    override fun requestRefund(ctx: OrderContext): Money {
        if (ctx.refundRequested) reject("requestRefund", "refund already requested")
        if (!ctx.paidOnline) reject("requestRefund", "cash on delivery cannot be refunded online")
        if (!withinRefundWindow(ctx)) throw RefundWindowExpiredException(REFUND_WINDOW)
        ctx.markRefundRequested()
        return ctx.amountPaid
    }

    private fun withinRefundWindow(ctx: OrderContext): Boolean {
        val deliveredAt = checkNotNull(ctx.deliveredAt) { "DELIVERED order without deliveredAt" }
        return Duration.between(deliveredAt, ctx.now) <= REFUND_WINDOW
    }

    override fun rate(ctx: OrderContext, stars: Int) {
        if (stars !in 1..5) throw ValidationException("Rating must be between 1 and 5")
        if (ctx.rated) reject("rate", "order already rated")
        ctx.recordRating(stars)
    }
}

object CancelledState : OrderState(OrderStatus.CANCELLED)
