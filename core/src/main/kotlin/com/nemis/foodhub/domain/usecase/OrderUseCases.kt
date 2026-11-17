package com.nemis.foodhub.domain.usecase

import com.nemis.foodhub.domain.ValidationException
import com.nemis.foodhub.domain.invoice.Invoice
import com.nemis.foodhub.domain.invoice.InvoiceFormat
import com.nemis.foodhub.domain.model.CartItem
import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.order.CancellationResult
import com.nemis.foodhub.domain.order.Order
import com.nemis.foodhub.domain.payment.RefundReceipt
import com.nemis.foodhub.domain.payment.Refundable
import com.nemis.foodhub.domain.pricing.PricingService
import com.nemis.foodhub.domain.region.RegionRegistry
import com.nemis.foodhub.domain.repository.OrderRepository
import com.nemis.foodhub.domain.repository.getOrThrow
import java.time.Clock

/** Finds the processor an order was paid with and refunds through it, if that method supports refunds. */
class RefundExecutor(private val regions: RegionRegistry) {

    fun refundableProcessorFor(order: Order): Refundable? =
        regions.forRegion(order.region.code).createPaymentProcessor(order.paymentMethod) as? Refundable

    fun refund(order: Order, amount: Money): RefundReceipt {
        val processor = refundableProcessorFor(order)
            ?: throw ValidationException("Payments made by ${order.paymentMethod} cannot be refunded online")
        return processor.refund(order.receipt, amount)
    }
}

/**
 * The state decides fee and refund amount; this use case only persists and moves the money.
 * Known limitation: if the gateway refund fails after the state change, the order stays CANCELLED and the
 * refund must be retried (a production system would queue it).
 */
class CancelOrderUseCase(private val orders: OrderRepository, private val refunds: RefundExecutor) {
    fun execute(orderId: OrderId): CancellationResult {
        val order = orders.getOrThrow(orderId)
        val result = order.cancel()
        orders.save(order)
        if (result.refund.isPositive) refunds.refund(order, result.refund)
        return result
    }
}

class RefundOrderUseCase(private val orders: OrderRepository, private val refunds: RefundExecutor) {
    fun execute(orderId: OrderId): Money {
        val order = orders.getOrThrow(orderId)
        if (refunds.refundableProcessorFor(order) == null) {
            throw ValidationException("Payments made by ${order.paymentMethod} cannot be refunded online")
        }
        val amount = order.requestRefund()
        orders.save(order)
        refunds.refund(order, amount)
        return amount
    }
}

/**
 * Only allowed while the state permits editing (CREATED). Price is recalculated with the order's own region
 * rules. Known limitation: a price difference is not settled with the payment gateway.
 */
class EditOrderItemsUseCase(
    private val orders: OrderRepository,
    private val pricing: PricingService,
    private val regions: RegionRegistry
) {
    fun execute(orderId: OrderId, newItems: List<CartItem>) {
        val order = orders.getOrThrow(orderId)
        if (newItems.isEmpty()) throw ValidationException("An order needs at least one item")
        if (newItems.any { it.item.restaurantId != order.restaurantId }) {
            throw ValidationException("New items must come from the order's restaurant ${order.restaurantId}")
        }
        val tax = regions.forRegion(order.region.code).createTaxCalculator()
        val newPrice = pricing.price(newItems, order.promo, order.price.deliveryFee, tax)
        order.editItems(newItems, newPrice)
        orders.save(order)
    }
}

/** Renders the invoice of an order in the notation of the region the order was placed in (HTML or CSV). */
class ExportInvoiceUseCase(
    private val orders: OrderRepository,
    private val regions: RegionRegistry,
    private val clock: Clock
) {
    fun execute(orderId: OrderId, format: InvoiceFormat): String {
        val order = orders.getOrThrow(orderId)
        val formatter = regions.forRegion(order.region.code).createInvoiceFormatter()
        return formatter.format(Invoice.from(order, clock.instant()), format)
    }
}

/** Stand-in for the Restaurant and Courier apps: drives the order through its lifecycle. */
class OrderLifecycleService(private val orders: OrderRepository) {
    fun confirm(id: OrderId) = apply(id) { it.confirm() }
    fun startPreparing(id: OrderId) = apply(id) { it.startPreparing() }
    fun startDelivery(id: OrderId) = apply(id) { it.startDelivery() }
    fun markDelivered(id: OrderId) = apply(id) { it.markDelivered() }

    private fun apply(id: OrderId, action: (Order) -> Unit) {
        val order = orders.getOrThrow(id)
        action(order)
        orders.save(order)
    }
}
