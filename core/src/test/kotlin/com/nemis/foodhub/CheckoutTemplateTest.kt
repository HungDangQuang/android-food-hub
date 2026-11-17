package com.nemis.foodhub

import com.nemis.foodhub.data.InMemoryOrderRepository
import com.nemis.foodhub.data.InMemoryPromoCatalog
import com.nemis.foodhub.data.SequentialIdGenerator
import com.nemis.foodhub.data.SimulatedInventoryService
import com.nemis.foodhub.domain.OutOfStockException
import com.nemis.foodhub.domain.PaymentFailedException
import com.nemis.foodhub.domain.ValidationException
import com.nemis.foodhub.domain.checkout.CheckoutContext
import com.nemis.foodhub.domain.checkout.CheckoutProcess
import com.nemis.foodhub.domain.checkout.CheckoutServices
import com.nemis.foodhub.domain.checkout.DeliveryCheckout
import com.nemis.foodhub.domain.checkout.ScheduledCheckout
import com.nemis.foodhub.domain.model.CartItem
import com.nemis.foodhub.domain.model.Currency
import com.nemis.foodhub.domain.model.MenuItem
import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderStatus
import com.nemis.foodhub.domain.model.OrderType
import com.nemis.foodhub.domain.notification.MultiChannelNotifier
import com.nemis.foodhub.domain.order.Order
import com.nemis.foodhub.domain.order.SynchronousOrderEventPublisher
import com.nemis.foodhub.domain.payment.PaymentMethod
import com.nemis.foodhub.domain.payment.PaymentReceipt
import com.nemis.foodhub.domain.payment.SimulatedPaymentProcessor
import com.nemis.foodhub.domain.pricing.PriceBreakdown
import com.nemis.foodhub.domain.pricing.PricingService
import com.nemis.foodhub.domain.region.RegionRegistry
import com.nemis.foodhub.domain.region.vn.VietnamRegionFactory
import com.nemis.foodhub.domain.repository.InventoryService
import com.nemis.foodhub.domain.repository.OrderRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.Duration

/** C4: Template Method. */
class CheckoutTemplateTest {

    private val clock = MutableClock()
    private val app = Fixtures.newApp(clock)

    // ---- the template fixes the order of steps ----

    private class RecordingCheckout(services: CheckoutServices, val log: MutableList<String>) : CheckoutProcess(services) {
        override fun validate(ctx: CheckoutContext) { log += "validate"; super.validate(ctx) }
        override fun calculatePrice(ctx: CheckoutContext): PriceBreakdown = super.calculatePrice(ctx).also { log += "price" }
        override fun pay(ctx: CheckoutContext, price: PriceBreakdown): PaymentReceipt = super.pay(ctx, price).also { log += "pay" }
        override fun confirm(ctx: CheckoutContext, price: PriceBreakdown, receipt: PaymentReceipt): Order =
            super.confirm(ctx, price, receipt).also { log += "confirm" }
        override fun notifyCustomer(order: Order) { log += "notify"; super.notifyCustomer(order) }
        override fun validateTypeRules(ctx: CheckoutContext) { log += "typeRules" }
        override fun placedMessage(order: Order) = "placed"
    }

    @Test
    fun stepsRunInTheFixedOrderValidatePricePayConfirmNotify() {
        val log = mutableListOf<String>()
        RecordingCheckout(app.services, log).checkout(Fixtures.vnRequest(app))
        assertEquals(listOf("validate", "typeRules", "price", "pay", "confirm", "notify"), log)
    }

    @Test
    fun theTemplateMethodItselfCannotBeOverridden() {
        val method = CheckoutProcess::class.java.getMethod("checkout", com.nemis.foodhub.domain.checkout.CheckoutRequest::class.java)
        assertTrue(Modifier.isFinal(method.modifiers))
    }

    @Test
    fun noStepAfterAFailedStepIsExecuted() {
        val log = mutableListOf<String>()
        val process = RecordingCheckout(app.services, log)
        assertThrows(PaymentFailedException::class.java) {
            process.checkout(Fixtures.vnRequest(app, payer = SimulatedPaymentProcessor.DECLINED_PAYER))
        }
        assertEquals(listOf("validate", "typeRules", "price"), log)
    }

    // ---- what differs per order type ----

    @Test
    fun deliveryOrderIsChargedTheDeliveryFee() {
        val id = app.place(OrderType.DELIVERY).orderId
        assertEquals(Money.of(15_000, Currency.VND), app.order(id).price.deliveryFee)
        assertEquals(DEFAULT_VN_TOTAL, app.order(id).price.total)
    }

    @Test
    fun pickupOrderHasNoDeliveryFee() {
        val id = app.place(OrderType.PICKUP).orderId
        assertEquals(Money.zero(Currency.VND), app.order(id).price.deliveryFee)
        assertEquals(Money.of(121_000, Currency.VND), app.order(id).price.total) // 110,000 + 11,000 VAT
    }

    @Test
    fun deliveryNeedsAnAddress() {
        listOf(null, "", "   ").forEach { address ->
            assertThrows(ValidationException::class.java) {
                app.facade.checkout(Fixtures.vnRequest(app, address = address))
            }
        }
    }

    @Test
    fun pickupCannotBeScheduled() {
        assertThrows(ValidationException::class.java) {
            app.place(OrderType.PICKUP, scheduledAt = clock.instant().plus(Duration.ofHours(2)))
        }
    }

    @Test
    fun scheduledOrderNeedsATimeBetween30MinutesAndSevenDaysAhead() {
        val now = clock.instant()
        assertThrows(ValidationException::class.java) { app.place(OrderType.SCHEDULED) }
        assertThrows(ValidationException::class.java) { app.place(OrderType.SCHEDULED, scheduledAt = now.plus(Duration.ofMinutes(29))) }
        assertThrows(ValidationException::class.java) { app.place(OrderType.SCHEDULED, scheduledAt = now.plus(Duration.ofDays(7)).plusSeconds(1)) }

        val ok = app.place(OrderType.SCHEDULED, scheduledAt = now.plus(ScheduledCheckout.MIN_LEAD_TIME))
        assertEquals(now.plus(ScheduledCheckout.MIN_LEAD_TIME), app.order(ok.orderId).scheduledAt)
        assertEquals(DEFAULT_VN_TOTAL, ok.price.total)
    }

    // ---- common validation ----

    @Test
    fun promoCodeReducesTheTaxableAmount() {
        val confirmation = app.place(promo = "SAVE10")
        assertEquals(Money.of(11_000, Currency.VND), confirmation.price.discount)
        assertEquals(Money.of(9_900, Currency.VND), confirmation.price.tax)
        assertEquals(Money.of(123_900, Currency.VND), confirmation.price.total)
    }

    @Test
    fun unknownPromoCodeIsRejected() {
        assertThrows(ValidationException::class.java) { app.place(promo = "NOPE") }
    }

    @Test
    fun emptyCartIsRejected() {
        assertThrows(ValidationException::class.java) {
            app.facade.checkout(Fixtures.vnRequest(app).copy(items = emptyList()))
        }
    }

    @Test
    fun cartMustComeFromOneRestaurantAndMatchTheRegionCurrency() {
        val mixed = Fixtures.items(app) + CartItem(app.menu.findItem("cheeseburger")!!, 1)
        assertThrows(ValidationException::class.java) { app.facade.checkout(Fixtures.vnRequest(app).copy(items = mixed)) }

        val dollars = listOf(CartItem(app.menu.findItem("cheeseburger")!!, 1))
        assertThrows(ValidationException::class.java) { app.facade.checkout(Fixtures.vnRequest(app).copy(items = dollars)) }
    }

    @Test
    fun outOfStockIsRejectedBeforeAnyMoneyMoves() {
        val tight = com.nemis.foodhub.app.AppContainer(clock = clock, initialStock = mapOf("pho-bo" to 1))
        assertThrows(OutOfStockException::class.java) { tight.place(quantity = 2) }
        assertEquals(1, tight.inventory.stockOf("pho-bo"))
    }

    // ---- failure handling ----

    @Test
    fun declinedPaymentLeavesNoOrderAndKeepsTheStock() {
        assertThrows(PaymentFailedException::class.java) { app.place(payer = SimulatedPaymentProcessor.DECLINED_PAYER) }
        assertNull(app.orders.findById(OrderId("ORD-0001")))
        assertEquals(50, app.inventory.stockOf("pho-bo"))
        assertTrue(app.sentMessages.all().isEmpty())
    }

    @Test
    fun successfulCheckoutReservesStockSavesTheOrderAndNotifies() {
        val confirmation = app.place()
        assertEquals(OrderStatus.CREATED, confirmation.status)
        assertEquals(48, app.inventory.stockOf("pho-bo"))
        assertEquals(OrderStatus.CREATED, app.order(confirmation.orderId).status)
        assertEquals(3, app.sentMessages.all().size)
    }

    private class FlakyInventory : InventoryService {
        override fun isAvailable(itemId: String, quantity: Int) = true
        override fun reserve(items: List<CartItem>) = throw OutOfStockException("race-condition")
        override fun release(items: List<CartItem>) {}
    }

    private class FailingOrderRepository : OrderRepository {
        override fun save(order: Order) = throw IllegalStateException("db down")
        override fun findById(id: OrderId): Order? = null
    }

    private fun servicesWith(inventory: InventoryService, orders: OrderRepository, factory: SpyRegionFactory) = CheckoutServices(
        RegionRegistry(listOf(factory)), PricingService(), InMemoryPromoCatalog(), inventory, orders,
        SynchronousOrderEventPublisher(), MultiChannelNotifier(emptyList()), clock, SequentialIdGenerator()
    )

    @Test
    fun paymentIsRefundedWhenStockDisappearsBetweenValidationAndConfirmation() {
        val factory = SpyRegionFactory()
        val process = DeliveryCheckout(servicesWith(FlakyInventory(), InMemoryOrderRepository(), factory))

        assertThrows(OutOfStockException::class.java) { process.checkout(Fixtures.vnRequest(app)) }

        assertEquals(listOf(DEFAULT_VN_TOTAL), factory.processor.refunds)
    }

    @Test
    fun stockIsReleasedAndPaymentRefundedWhenSavingTheOrderFails() {
        val factory = SpyRegionFactory()
        val inventory = SimulatedInventoryService(mapOf("pho-bo" to 10))
        val process = DeliveryCheckout(servicesWith(inventory, FailingOrderRepository(), factory))

        assertThrows(IllegalStateException::class.java) { process.checkout(Fixtures.vnRequest(app)) }

        assertEquals(10, inventory.stockOf("pho-bo"))
        assertEquals(listOf(DEFAULT_VN_TOTAL), factory.processor.refunds)
    }

    @Test
    fun cartItemsKeepTheirMenuPrices() {
        val item = MenuItem("x", "r", "X", Money.of(10, Currency.VND))
        assertEquals(Money.of(30, Currency.VND), CartItem(item, 3).lineTotal)
        assertThrows(IllegalArgumentException::class.java) { CartItem(item, 0) }
        assertEquals(VietnamRegionFactory.REGION.currency, Currency.VND)
    }
}
