package com.nemis.foodhub

import com.nemis.foodhub.domain.IllegalOrderOperationException
import com.nemis.foodhub.domain.RefundWindowExpiredException
import com.nemis.foodhub.domain.ValidationException
import com.nemis.foodhub.domain.model.Currency
import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.model.OrderStatus
import com.nemis.foodhub.domain.model.OrderStatus.CANCELLED
import com.nemis.foodhub.domain.model.OrderStatus.CONFIRMED
import com.nemis.foodhub.domain.model.OrderStatus.CREATED
import com.nemis.foodhub.domain.model.OrderStatus.DELIVERED
import com.nemis.foodhub.domain.model.OrderStatus.DELIVERING
import com.nemis.foodhub.domain.model.OrderStatus.PREPARING
import com.nemis.foodhub.domain.model.OrderType
import com.nemis.foodhub.domain.order.CANCELLATION_FEE_PERCENT_AFTER_CONFIRM
import com.nemis.foodhub.domain.order.Order
import com.nemis.foodhub.domain.order.OrderCapabilities
import com.nemis.foodhub.domain.payment.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/** C1: State pattern. The behaviour table of the assignment, one test per rule. */
class OrderStateTest {

    private val clock = MutableClock()
    private val app = Fixtures.newApp(clock)

    // ---- valid transitions ----

    @Test
    fun deliveryOrderWalksTheWholeLifecycleInOrder() {
        val order = orderIn(app, CREATED)
        val log = EventLog().also { app.events.subscribe(it) }

        order.confirm(); order.startPreparing(); order.startDelivery(); order.markDelivered()

        assertEquals(listOf(CONFIRMED, PREPARING, DELIVERING, DELIVERED), log.statuses)
        assertEquals(
            listOf(CREATED to CONFIRMED, CONFIRMED to PREPARING, PREPARING to DELIVERING, DELIVERING to DELIVERED),
            order.history.map { it.from to it.to }
        )
        assertEquals(DELIVERED, order.status)
    }

    @Test
    fun pickupOrderGoesFromPreparingStraightToDelivered() {
        val order = orderIn(app, PREPARING, type = OrderType.PICKUP)

        val ex = assertThrows(IllegalOrderOperationException::class.java) { order.startDelivery() }
        assertTrue(ex.message!!.contains("pickup"))

        order.markDelivered()
        assertEquals(DELIVERED, order.status)
    }

    @Test
    fun deliveryOrderCannotSkipTheCourierStep() {
        val order = orderIn(app, PREPARING, type = OrderType.DELIVERY)
        assertThrows(IllegalOrderOperationException::class.java) { order.markDelivered() }
        assertEquals(PREPARING, order.status)
    }

    @Test
    fun scheduledOrderUsesTheSameLifecycleAsDelivery() {
        val order = orderIn(app, DELIVERED, type = OrderType.SCHEDULED).also { }
        assertEquals(DELIVERED, order.status)
    }

    // ---- rules of the behaviour table ----

    @Test
    fun cancelInCreatedIsFreeAndRefundsEverything() {
        val order = orderIn(app, CREATED)
        val result = app.facade.cancelOrder(order.id)
        assertEquals(Money.zero(Currency.VND), result.fee)
        assertEquals(DEFAULT_VN_TOTAL, result.refund)
        assertEquals(CANCELLED, order.status)
    }

    @Test
    fun cancelInConfirmedCharges10PercentAndRefundsTheRest() {
        val order = orderIn(app, CONFIRMED)
        val result = app.facade.cancelOrder(order.id)
        assertEquals(10, CANCELLATION_FEE_PERCENT_AFTER_CONFIRM)
        assertEquals(Money.of(13_600, Currency.VND), result.fee)
        assertEquals(Money.of(122_400, Currency.VND), result.refund)
        assertEquals(result.fee + result.refund, DEFAULT_VN_TOTAL)
    }

    @Test
    fun editingItemsIsOnlyAllowedInCreated() {
        for (status in listOf(CONFIRMED, PREPARING, DELIVERING, DELIVERED, CANCELLED)) {
            val order = orderIn(Fixtures.newApp(), status)
            assertThrows("editItems in $status", IllegalOrderOperationException::class.java) {
                order.editItems(order.items, order.price)
            }
        }
        val created = orderIn(app, CREATED)
        created.editItems(created.items, created.price)
    }

    @Test
    fun cancelIsRejectedFromPreparingOnwards() {
        for (status in listOf(PREPARING, DELIVERING, DELIVERED, CANCELLED)) {
            val order = orderIn(Fixtures.newApp(), status)
            assertThrows("cancel in $status", IllegalOrderOperationException::class.java) { order.cancel() }
        }
    }

    // ---- invalid transitions are rejected explicitly, with no side effects ----

    private val allowedIn: Map<OrderStatus, Set<String>> = mapOf(
        CREATED to setOf("confirm", "cancel", "editItems"),
        CONFIRMED to setOf("startPreparing", "cancel"),
        PREPARING to setOf("startDelivery"),
        DELIVERING to setOf("markDelivered"),
        DELIVERED to setOf("requestRefund", "rate"),
        CANCELLED to emptySet()
    )

    private val operations: Map<String, (Order) -> Unit> = mapOf(
        "confirm" to { o -> o.confirm() },
        "startPreparing" to { o -> o.startPreparing() },
        "startDelivery" to { o -> o.startDelivery() },
        "markDelivered" to { o -> o.markDelivered() },
        "cancel" to { o -> o.cancel() },
        "editItems" to { o -> o.editItems(o.items, o.price) },
        "requestRefund" to { o -> o.requestRefund() },
        "rate" to { o -> o.rate(5) }
    )

    @Test
    fun everyOperationOutsideTheAllowedSetIsRejectedWithoutSideEffects() {
        var checked = 0
        for (status in OrderStatus.values()) {
            for ((name, operation) in operations) {
                if (name in allowedIn.getValue(status)) continue
                val freshApp = Fixtures.newApp()
                val order = orderIn(freshApp, status)
                val log = EventLog().also { freshApp.events.subscribe(it) }
                val historySize = order.history.size

                val ex = assertThrows("$name in $status", IllegalOrderOperationException::class.java) { operation(order) }

                assertEquals(status, ex.status)
                assertEquals(name, ex.operation)
                assertEquals(status, order.status)
                assertEquals(historySize, order.history.size)
                assertTrue("no event for a rejected operation", log.events.isEmpty())
                checked++
            }
        }
        assertEquals(6 * 8 - allowedIn.values.sumOf { it.size }, checked)
    }

    @Test
    fun cancelledIsTerminal() {
        val order = orderIn(app, CANCELLED)
        for ((_, operation) in operations) {
            assertThrows(IllegalOrderOperationException::class.java) { operation(order) }
        }
    }

    // ---- refund within 24h after delivery ----

    @Test
    fun refundIsAllowedWithin24HoursAndReturnsWhatWasPaid() {
        val order = orderIn(app, DELIVERED)
        clock.advance(Duration.ofHours(23).plusMinutes(59))
        assertEquals(DEFAULT_VN_TOTAL, app.facade.requestRefund(order.id))
        assertTrue(order.refundRequested)
        assertEquals(DELIVERED, order.status)
    }

    @Test
    fun refundIsStillAllowedAtExactly24Hours() {
        val order = orderIn(app, DELIVERED)
        clock.advance(Duration.ofHours(24))
        assertEquals(DEFAULT_VN_TOTAL, order.requestRefund())
    }

    @Test
    fun refundAfter24HoursIsRejected() {
        val order = orderIn(app, DELIVERED)
        clock.advance(Duration.ofHours(24).plusSeconds(1))
        assertThrows(RefundWindowExpiredException::class.java) { order.requestRefund() }
        assertFalse(order.refundRequested)
    }

    @Test
    fun refundCanOnlyBeRequestedOnce() {
        val order = orderIn(app, DELIVERED)
        order.requestRefund()
        assertThrows(IllegalOrderOperationException::class.java) { order.requestRefund() }
    }

    @Test
    fun cashOnDeliveryIsCollectedOnDeliveryAndCannotBeRefundedOnline() {
        val order = orderIn(app, CREATED, method = PaymentMethod.COD)
        assertTrue("nothing held before delivery", order.amountPaid.isZero)

        val cancelResult = app.facade.cancelOrder(order.id)
        assertTrue(cancelResult.fee.isZero && cancelResult.refund.isZero)

        val delivered = orderIn(app, DELIVERED, method = PaymentMethod.COD)
        assertEquals(DEFAULT_VN_TOTAL, delivered.amountPaid)
        assertThrows(ValidationException::class.java) { app.facade.requestRefund(delivered.id) }
        assertFalse("rejected before the state changed", delivered.refundRequested)
    }

    // ---- rating ----

    @Test
    fun ratingIsOnlyAfterDeliveryAndOnlyOnce() {
        val order = orderIn(app, DELIVERED)
        assertThrows(ValidationException::class.java) { order.rate(0) }
        assertThrows(ValidationException::class.java) { order.rate(6) }
        order.rate(4)
        assertEquals(4, order.rating)
        assertThrows(IllegalOrderOperationException::class.java) { order.rate(5) }
    }

    // ---- capabilities (what the UI may offer) come from the state ----

    @Test
    fun capabilitiesFollowTheBehaviourTable() {
        val expected = mapOf(
            CREATED to OrderCapabilities(canEditItems = true, canCancel = true),
            CONFIRMED to OrderCapabilities(canCancel = true, cancellationFeePercent = 10),
            PREPARING to OrderCapabilities(),
            DELIVERING to OrderCapabilities(canTrackCourier = true),
            DELIVERED to OrderCapabilities(canRequestRefund = true, canRate = true),
            CANCELLED to OrderCapabilities()
        )
        for ((status, capabilities) in expected) {
            assertEquals(status.name, capabilities, orderIn(Fixtures.newApp(), status).capabilities)
        }
    }

    @Test
    fun eventsCarryTheCapabilitiesOfTheNewState() {
        val order = orderIn(app, CREATED)
        val log = EventLog().also { app.events.subscribe(it, order.id) }
        order.confirm()
        val event = log.events.single()
        assertEquals(CREATED, event.previous)
        assertEquals(CONFIRMED, event.current)
        assertEquals(10, event.capabilities.cancellationFeePercent)
        assertNotNull(event.occurredAt)
    }
}
