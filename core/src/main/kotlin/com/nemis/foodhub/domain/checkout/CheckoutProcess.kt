package com.nemis.foodhub.domain.checkout

import com.nemis.foodhub.domain.DomainException
import com.nemis.foodhub.domain.OutOfStockException
import com.nemis.foodhub.domain.ValidationException
import com.nemis.foodhub.domain.model.Address
import com.nemis.foodhub.domain.model.CartItem
import com.nemis.foodhub.domain.model.Customer
import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderStatus
import com.nemis.foodhub.domain.model.OrderType
import com.nemis.foodhub.domain.notification.CustomerNotifier
import com.nemis.foodhub.domain.order.Order
import com.nemis.foodhub.domain.order.OrderCapabilities
import com.nemis.foodhub.domain.order.OrderEventPublisher
import com.nemis.foodhub.domain.payment.PaymentMethod
import com.nemis.foodhub.domain.payment.PaymentReceipt
import com.nemis.foodhub.domain.payment.PaymentRequest
import com.nemis.foodhub.domain.payment.Refundable
import com.nemis.foodhub.domain.pricing.PriceBreakdown
import com.nemis.foodhub.domain.pricing.PricingService
import com.nemis.foodhub.domain.pricing.PromoCatalog
import com.nemis.foodhub.domain.pricing.PromoCode
import com.nemis.foodhub.domain.region.RegionFactory
import com.nemis.foodhub.domain.region.RegionRegistry
import com.nemis.foodhub.domain.repository.IdGenerator
import com.nemis.foodhub.domain.repository.InventoryService
import com.nemis.foodhub.domain.repository.OrderRepository
import java.time.Clock
import java.time.Instant

data class CheckoutRequest(
    val customer: Customer,
    val items: List<CartItem>,
    val type: OrderType,
    val regionCode: String,
    val paymentMethod: PaymentMethod,
    val payerRef: String,
    val promoCode: String? = null,
    val deliveryAddress: Address? = null,
    val scheduledAt: Instant? = null
)

data class OrderConfirmation(
    val orderId: OrderId,
    val status: OrderStatus,
    val capabilities: OrderCapabilities,
    val price: PriceBreakdown,
    val message: String
)

/** Collaborators shared by all checkout flows. */
class CheckoutServices(
    val regions: RegionRegistry,
    val pricing: PricingService,
    val promos: PromoCatalog,
    val inventory: InventoryService,
    val orders: OrderRepository,
    val events: OrderEventPublisher,
    val notifier: CustomerNotifier,
    val clock: Clock,
    val ids: IdGenerator
)

/** Per-checkout working data passed between the steps. */
class CheckoutContext(val request: CheckoutRequest, val factory: RegionFactory, val orderId: OrderId) {
    val region get() = factory.region
    var promo: PromoCode? = null
}

/**
 * Template Method (C4). [checkout] fixes the order of the steps and cannot be overridden:
 * validate -> price -> pay -> confirm -> notify.
 * Order types differ only in the hooks: [validateTypeRules], [deliveryFee], [placedMessage]. The steps themselves are
 * private, so no subclass can skip a common rule (e.g. the stock check) or reorder the steps.
 *
 * Failure handling: payment failure leaves no trace; if confirmation fails after the money was taken, the
 * payment is refunded and the inventory released before the error is rethrown.
 */
abstract class CheckoutProcess(protected val services: CheckoutServices) {

    fun checkout(request: CheckoutRequest): OrderConfirmation {
        val factory = services.regions.forRegion(request.regionCode)
        val ctx = CheckoutContext(request, factory, OrderId(services.ids.next()))
        validate(ctx)
        val price = calculatePrice(ctx)
        val receipt = pay(ctx, price)
        val order = confirm(ctx, price, receipt)
        notifyCustomer(order)
        return OrderConfirmation(order.id, order.status, order.capabilities, order.price, placedMessage(order))
    }

    // ---- fixed steps: private, so a subclass can neither skip nor reorder them ----

    private fun validate(ctx: CheckoutContext) {
        val request = ctx.request
        if (request.items.isEmpty()) throw ValidationException("Cart is empty")
        if (request.items.map { it.item.restaurantId }.distinct().size != 1) {
            throw ValidationException("An order must contain items from exactly one restaurant")
        }
        if (request.items.any { it.item.price.currency != ctx.region.currency }) {
            throw ValidationException("Cart currency does not match region ${ctx.region.code}")
        }
        request.items.forEach {
            if (!services.inventory.isAvailable(it.item.id, it.quantity)) throw OutOfStockException(it.item.id)
        }
        ctx.promo = request.promoCode?.let {
            services.promos.find(it) ?: throw ValidationException("Unknown promo code '$it'")
        }
        validateTypeRules(ctx)
    }

    private fun calculatePrice(ctx: CheckoutContext): PriceBreakdown =
        services.pricing.price(ctx.request.items, ctx.promo, deliveryFee(ctx), ctx.factory.createTaxCalculator())

    private fun pay(ctx: CheckoutContext, price: PriceBreakdown): PaymentReceipt {
        val processor = ctx.factory.createPaymentProcessor(ctx.request.paymentMethod)
        return processor.pay(PaymentRequest(ctx.orderId.value, price.total, ctx.request.paymentMethod, ctx.request.payerRef))
    }

    private fun confirm(ctx: CheckoutContext, price: PriceBreakdown, receipt: PaymentReceipt): Order {
        val items = ctx.request.items
        try {
            services.inventory.reserve(items)
        } catch (e: DomainException) {
            refundQuietly(ctx, receipt)
            throw e
        }
        val order = Order(
            id = ctx.orderId,
            customer = ctx.request.customer,
            restaurantId = items.first().item.restaurantId,
            type = ctx.request.type,
            region = ctx.region,
            paymentMethod = ctx.request.paymentMethod,
            deliveryAddress = ctx.request.deliveryAddress,
            scheduledAt = ctx.request.scheduledAt,
            promo = ctx.promo,
            items = items,
            price = price,
            receipt = receipt,
            clock = services.clock,
            publisher = services.events
        )
        try {
            services.orders.save(order)
        } catch (e: RuntimeException) {
            services.inventory.release(items)
            refundQuietly(ctx, receipt)
            throw e
        }
        return order
    }

    private fun notifyCustomer(order: Order) =
        services.notifier.notifyOrderUpdate(order.customer, order.id, placedMessage(order))

    // ---- hooks that differ per order type ----

    protected abstract fun validateTypeRules(ctx: CheckoutContext)

    protected open fun deliveryFee(ctx: CheckoutContext): Money = Money.zero(ctx.region.currency)

    protected abstract fun placedMessage(order: Order): String

    private fun refundQuietly(ctx: CheckoutContext, receipt: PaymentReceipt) {
        val processor = ctx.factory.createPaymentProcessor(receipt.method)
        if (receipt.collected && processor is Refundable) processor.refund(receipt, receipt.amount)
    }
}
