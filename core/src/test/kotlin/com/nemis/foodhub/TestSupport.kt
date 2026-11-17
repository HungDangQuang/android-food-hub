package com.nemis.foodhub

import com.nemis.foodhub.app.AppContainer
import com.nemis.foodhub.data.InMemoryMenuRepository
import com.nemis.foodhub.domain.checkout.CheckoutRequest
import com.nemis.foodhub.domain.checkout.OrderConfirmation
import com.nemis.foodhub.domain.model.Address
import com.nemis.foodhub.domain.model.CartItem
import com.nemis.foodhub.domain.model.ChannelType
import com.nemis.foodhub.domain.model.Currency
import com.nemis.foodhub.domain.model.Customer
import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderStatus
import com.nemis.foodhub.domain.model.OrderType
import com.nemis.foodhub.domain.order.Order
import com.nemis.foodhub.domain.order.OrderStatusChanged
import com.nemis.foodhub.domain.order.OrderStatusListener
import com.nemis.foodhub.domain.payment.PaymentMethod
import com.nemis.foodhub.domain.payment.PaymentProcessor
import com.nemis.foodhub.domain.payment.PaymentReceipt
import com.nemis.foodhub.domain.payment.RefundReceipt
import com.nemis.foodhub.domain.payment.Refundable
import com.nemis.foodhub.domain.payment.SimulatedPaymentProcessor
import com.nemis.foodhub.domain.pricing.TaxCalculator
import com.nemis.foodhub.domain.invoice.InvoiceFormatter
import com.nemis.foodhub.domain.region.RegionFactory
import com.nemis.foodhub.domain.region.vn.VietnamInvoiceFormatter
import com.nemis.foodhub.domain.region.vn.VietnamRegionFactory
import com.nemis.foodhub.domain.region.vn.VietnamVatCalculator
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

class MutableClock(start: Instant = Instant.parse("2026-01-15T10:00:00Z")) : Clock() {
    @Volatile
    private var now: Instant = start

    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

class EventLog : OrderStatusListener {
    val events = CopyOnWriteArrayList<OrderStatusChanged>()
    override fun onOrderStatusChanged(event: OrderStatusChanged) {
        events.add(event)
    }

    val statuses get() = events.map { it.current }
}

object Fixtures {
    val customer = Customer(
        "c-1", "Hung", "+84900000001", "hung@example.com",
        setOf(ChannelType.PUSH, ChannelType.SMS, ChannelType.EMAIL)
    )

    fun newApp(clock: Clock = MutableClock()) = AppContainer(clock = clock)

    fun items(app: AppContainer, itemId: String = "pho-bo", quantity: Int = 2): List<CartItem> =
        listOf(CartItem(app.menu.findItem(itemId)!!, quantity))

    fun vnRequest(
        app: AppContainer,
        type: OrderType = OrderType.DELIVERY,
        method: PaymentMethod = PaymentMethod.CARD,
        payer: String = "visa-4242",
        quantity: Int = 2,
        promo: String? = null,
        scheduledAt: Instant? = null,
        address: String? = "12 Nguyen Hue"
    ) = CheckoutRequest(
        customer, items(app, "pho-bo", quantity), type, "VN", method, payer,
        promoCode = promo, deliveryAddress = address?.let { Address(it) }, scheduledAt = scheduledAt
    )
}

/** 2 x Pho bo (55,000 VND), VN delivery: subtotal 110,000 + fee 15,000 + VAT 11,000 = 136,000 VND. */
val DEFAULT_VN_TOTAL = Money.of(136_000, Currency.VND)

fun AppContainer.place(
    type: OrderType = OrderType.DELIVERY,
    method: PaymentMethod = PaymentMethod.CARD,
    payer: String = "visa-4242",
    quantity: Int = 2,
    promo: String? = null,
    scheduledAt: Instant? = null
): OrderConfirmation = facade.checkout(Fixtures.vnRequest(this, type, method, payer, quantity, promo, scheduledAt))

fun AppContainer.order(id: OrderId): Order = orders.findById(id)!!

/** Builds an order that is currently in [status]. */
fun orderIn(
    app: AppContainer,
    status: OrderStatus,
    type: OrderType = OrderType.DELIVERY,
    method: PaymentMethod = PaymentMethod.CARD
): Order {
    val scheduledAt = if (type == OrderType.SCHEDULED) app.clock.instant().plus(Duration.ofHours(2)) else null
    val id = app.place(type, method, scheduledAt = scheduledAt).orderId
    val lifecycle = app.lifecycle
    when (status) {
        OrderStatus.CREATED -> {}
        OrderStatus.CONFIRMED -> lifecycle.confirm(id)
        OrderStatus.PREPARING -> {
            lifecycle.confirm(id); lifecycle.startPreparing(id)
        }
        OrderStatus.DELIVERING -> {
            lifecycle.confirm(id); lifecycle.startPreparing(id); lifecycle.startDelivery(id)
        }
        OrderStatus.DELIVERED -> {
            lifecycle.confirm(id); lifecycle.startPreparing(id)
            if (type.requiresCourier) lifecycle.startDelivery(id)
            lifecycle.markDelivered(id)
        }
        OrderStatus.CANCELLED -> app.facade.cancelOrder(id)
    }
    return app.order(id)
}

/** Payment processor that records refunds, used to verify compensation. */
class SpyProcessor : SimulatedPaymentProcessor(PaymentMethod.CARD, Currency.VND, "Spy"), Refundable {
    val refunds = mutableListOf<Money>()
    override fun refund(receipt: PaymentReceipt, amount: Money): RefundReceipt {
        refunds.add(amount)
        return RefundReceipt("spy-refund", amount)
    }
}

class SpyRegionFactory : RegionFactory(VietnamRegionFactory.REGION) {
    val processor = SpyProcessor()
    override fun newPaymentProcessor(method: PaymentMethod): PaymentProcessor = processor
    override fun createTaxCalculator(): TaxCalculator = VietnamVatCalculator()
    override fun createInvoiceFormatter(): InvoiceFormatter = VietnamInvoiceFormatter()
}
