package com.nemis.foodhub

import com.nemis.foodhub.app.AppContainer
import com.nemis.foodhub.domain.IllegalOrderOperationException
import com.nemis.foodhub.domain.OrderNotFoundException
import com.nemis.foodhub.domain.OutOfStockException
import com.nemis.foodhub.domain.ValidationException
import com.nemis.foodhub.domain.facade.DefaultOrderFacade
import com.nemis.foodhub.domain.facade.OrderFacade
import com.nemis.foodhub.domain.model.CartItem
import com.nemis.foodhub.domain.model.Currency
import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderStatus
import com.nemis.foodhub.domain.model.OrderType
import com.nemis.foodhub.domain.notification.CustomerNotifier
import com.nemis.foodhub.domain.payment.PaymentMethod
import com.nemis.foodhub.domain.payment.PaymentProcessor
import com.nemis.foodhub.domain.pricing.PricingService
import com.nemis.foodhub.domain.region.RegionRegistry
import com.nemis.foodhub.domain.repository.InventoryService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/** C7: Facade, plus end-to-end scenarios through it. */
class OrderFacadeTest {

    private val app = Fixtures.newApp()

    @Test
    fun oneCallPlacesAnOrderEndToEnd() {
        val confirmation = app.facade.checkout(Fixtures.vnRequest(app))
        val summary = app.facade.getOrder(confirmation.orderId)
        assertEquals(OrderStatus.CREATED, summary.status)
        assertEquals(OrderType.DELIVERY, summary.type)
        assertEquals(DEFAULT_VN_TOTAL, summary.price.total)
        assertEquals(1, summary.items.size)
    }

    @Test
    fun fullCustomerJourneyThroughTheFacade() {
        val id = app.place().orderId
        app.lifecycle.confirm(id); app.lifecycle.startPreparing(id)
        app.lifecycle.startDelivery(id); app.lifecycle.markDelivered(id)

        app.facade.rateOrder(id, 5)
        assertEquals(DEFAULT_VN_TOTAL, app.facade.requestRefund(id))
        assertEquals(5, app.order(id).rating)
    }

    @Test
    fun editingItemsRecalculatesThePriceWithTheOrdersOwnRegionRules() {
        val id = app.place().orderId
        app.facade.editOrderItems(id, Fixtures.items(app, "pho-bo", 1))
        // 55,000 + 15,000 delivery + 5,500 VAT
        assertEquals(Money.of(75_500, Currency.VND), app.facade.getOrder(id).price.total)
    }

    @Test
    fun editingIsRejectedOnceTheRestaurantConfirmedAndEmptyEditsAreRefused() {
        val id = app.place().orderId
        assertThrows(ValidationException::class.java) { app.facade.editOrderItems(id, emptyList()) }
        app.lifecycle.confirm(id)
        assertThrows(IllegalOrderOperationException::class.java) {
            app.facade.editOrderItems(id, Fixtures.items(app, "pho-bo", 1))
        }
        assertEquals("a rejected edit does not touch the stock", 48, app.inventory.stockOf("pho-bo"))
    }

    // ---- stock follows the order ----

    @Test
    fun cancellingAnOrderPutsItsItemsBackInStock() {
        val id = app.place(quantity = 2).orderId
        assertEquals(48, app.inventory.stockOf("pho-bo"))

        app.facade.cancelOrder(id)

        assertEquals(50, app.inventory.stockOf("pho-bo"))
    }

    @Test
    fun editingItemsMovesTheDifferenceInAndOutOfStock() {
        val id = app.place(quantity = 2).orderId

        app.facade.editOrderItems(id, Fixtures.items(app, "pho-bo", 5))
        assertEquals(45, app.inventory.stockOf("pho-bo"))

        app.facade.editOrderItems(id, Fixtures.items(app, "ca-phe-sua", 1))
        assertEquals(50, app.inventory.stockOf("pho-bo"))
        assertEquals(49, app.inventory.stockOf("ca-phe-sua"))
    }

    @Test
    fun anEditBeyondTheStockIsRejectedAndChangesNothing() {
        val tight = AppContainer(clock = MutableClock(), initialStock = mapOf("pho-bo" to 3))
        val id = tight.place(quantity = 2).orderId
        val before = tight.facade.getOrder(id)

        assertThrows(OutOfStockException::class.java) {
            tight.facade.editOrderItems(id, Fixtures.items(tight, "pho-bo", 4))
        }

        assertEquals(before.items, tight.facade.getOrder(id).items)
        assertEquals(before.price, tight.facade.getOrder(id).price)
        assertEquals(1, tight.inventory.stockOf("pho-bo"))
    }

    @Test
    fun editingWithItemsOfAnotherRestaurantIsAValidationErrorAndChangesNothing() {
        val id = app.place().orderId
        val before = app.facade.getOrder(id)
        assertThrows(ValidationException::class.java) {
            app.facade.editOrderItems(id, Fixtures.items(app, "cheeseburger", 1))
        }
        assertEquals(before.items, app.facade.getOrder(id).items)
        assertEquals(before.price, app.facade.getOrder(id).price)
    }

    @Test
    fun unknownOrdersAreReported() {
        assertThrows(OrderNotFoundException::class.java) { app.facade.cancelOrder(OrderId("nope")) }
        assertThrows(OrderNotFoundException::class.java) { app.facade.getOrder(OrderId("nope")) }
    }

    @Test
    fun trackingThroughTheFacadeDeliversEventsOfThatOrderOnly() {
        val mine = app.place().orderId
        val other = app.place().orderId
        val log = EventLog()
        val subscription = app.facade.track(mine, log)

        app.lifecycle.confirm(mine); app.lifecycle.confirm(other)
        subscription.cancel()
        app.lifecycle.startPreparing(mine)

        assertEquals(listOf(OrderStatus.CONFIRMED), log.statuses)
    }

    // ---- the facade stays a facade ----

    private val forbidden = setOf<Class<*>>(
        InventoryService::class.java, PaymentProcessor::class.java, RegionRegistry::class.java,
        PricingService::class.java, CustomerNotifier::class.java
    )

    @Test
    fun facadeDoesNotKnowInventoryPricingPaymentOrNotification() {
        val fieldTypes = DefaultOrderFacade::class.java.declaredFields.map { it.type }
        assertTrue("fields: $fieldTypes", fieldTypes.none { it in forbidden })
        val publicMethods: List<Method> = OrderFacade::class.java.methods.toList()
        val signatureTypes = publicMethods.flatMap { it.parameterTypes.toList() + it.returnType }
        assertTrue(signatureTypes.none { it in forbidden })
    }

    @Test
    fun facadeApiStaysSmall() {
        assertTrue("OrderFacade has ${OrderFacade::class.java.methods.size} methods", OrderFacade::class.java.methods.size <= 8)
        assertFalse(DefaultOrderFacade::class.java.declaredFields.any { it.type == app.orders::class.java })
    }

    @Test
    fun facadeRefusesAnUnsupportedPaymentMethodCombinationOnlyThroughTheDomain() {
        // COD order: checkout works, refund is rejected by the use case, not by the facade.
        val id = app.place(method = PaymentMethod.COD).orderId
        assertEquals(OrderStatus.CREATED, app.order(id).status)
        assertThrows(ValidationException::class.java) { app.facade.requestRefund(id) }
        assertEquals(1, app.facade.getOrder(id).items.size)
        assertEquals(CartItem::class.java, app.facade.getOrder(id).items.first()::class.java)
    }
}
