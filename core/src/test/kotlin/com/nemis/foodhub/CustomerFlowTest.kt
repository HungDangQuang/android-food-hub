package com.nemis.foodhub

import com.nemis.foodhub.data.channel.SentMessageLog
import com.nemis.foodhub.domain.OrderNotFoundException
import com.nemis.foodhub.domain.model.ChannelType
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderStatus
import com.nemis.foodhub.domain.model.OrderStatus.CANCELLED
import com.nemis.foodhub.domain.model.OrderStatus.CONFIRMED
import com.nemis.foodhub.domain.model.OrderStatus.CREATED
import com.nemis.foodhub.domain.model.OrderStatus.DELIVERED
import com.nemis.foodhub.domain.invoice.InvoiceFormat
import com.nemis.foodhub.domain.model.OrderType
import com.nemis.foodhub.domain.payment.PaymentMethod
import com.nemis.foodhub.domain.pricing.PromoCode
import com.nemis.foodhub.presentation.InvoiceUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/**
 * Customer-facing capabilities of the assignment that are not a pattern of their own: menu, invoice export,
 * editing items, promotion messages, and a UI that only offers what the domain would accept.
 */
class CustomerFlowTest {

    private val clock = MutableClock()
    private val app = Fixtures.newApp(clock)

    // ---- menu ----

    @Test
    fun menuViewModelListsRestaurantsAndTheirItems() {
        val vm = app.newMenuViewModel()
        assertEquals(setOf("r-vn-pho", "r-us-burger"), vm.uiState.value.restaurantIds.toSet())
        assertTrue(vm.uiState.value.items.isEmpty())

        vm.showRestaurant("r-vn-pho")

        assertEquals("r-vn-pho", vm.uiState.value.selectedRestaurantId)
        assertEquals(listOf("pho-bo", "ca-phe-sua"), vm.uiState.value.items.map { it.id })
    }

    // ---- invoice export through the facade ----

    @Test
    fun invoiceIsRenderedInTheNotationOfTheOrdersRegion() {
        val vnId = app.place().orderId
        val html = app.facade.exportInvoice(vnId, InvoiceFormat.HTML)
        assertTrue(html.contains("Hóa đơn bán hàng"))
        assertTrue(html.contains("136.000 VND"))

        val csv = app.facade.exportInvoice(vnId, InvoiceFormat.CSV)
        assertTrue(csv.lines().first().contains("Thành tiền"))
    }

    @Test
    fun invoiceViewModelReportsReadyAndFailed() {
        val vm = app.newInvoiceViewModel(app.place().orderId)
        assertTrue(vm.uiState.value is InvoiceUiState.Idle)
        vm.export(InvoiceFormat.CSV)
        assertTrue((vm.uiState.value as InvoiceUiState.Ready).content.contains("Tổng cộng"))

        val missing = app.newInvoiceViewModel(OrderId("nope"))
        missing.export(InvoiceFormat.HTML)
        assertTrue(missing.uiState.value is InvoiceUiState.Failed)
        assertThrows(OrderNotFoundException::class.java) { app.facade.exportInvoice(OrderId("nope"), InvoiceFormat.HTML) }
    }

    // ---- editing items from the UI ----

    @Test
    fun editingItemsWorksOnlyWhileCreatedAndTheUiStateReflectsIt() {
        val id = app.place().orderId
        val vm = app.newOrderTrackingViewModel(id)
        val bigger = Fixtures.items(app, "pho-bo", 3)

        vm.editItems(bigger)
        assertNull(vm.uiState.value.error)
        assertEquals(3, app.facade.getOrder(id).items.single().quantity)

        app.lifecycle.confirm(id)
        vm.editItems(Fixtures.items(app, "pho-bo", 1))
        assertNotNull(vm.uiState.value.error)
        assertEquals(3, app.facade.getOrder(id).items.single().quantity)
        vm.onCleared()
    }

    // ---- capabilities describe this order, not only its state ----

    @Test
    fun theUiStopsOfferingRefundAndRatingOnceTheyWereUsed() {
        val id = orderIn(app, DELIVERED).id
        val vm = app.newOrderTrackingViewModel(id)
        assertTrue(vm.uiState.value.canRequestRefund && vm.uiState.value.canRate)

        vm.requestRefund()
        assertFalse(vm.uiState.value.canRequestRefund)
        assertTrue(vm.uiState.value.canRate)

        vm.rate(5)
        assertFalse(vm.uiState.value.canRate)
        assertNull(vm.uiState.value.error)
        vm.onCleared()
    }

    @Test
    fun cashOnDeliveryOrdersAreNeverOfferedAnOnlineRefund() {
        val order = orderIn(app, DELIVERED, method = PaymentMethod.COD)
        assertFalse(order.capabilities.canRequestRefund)
        assertTrue(order.capabilities.canRate)
    }

    @Test
    fun refundIsNoLongerOfferedAfterTheWindowCloses() {
        val order = orderIn(app, DELIVERED)
        assertTrue(order.capabilities.canRequestRefund)
        clock.advance(Duration.ofHours(24).plusSeconds(1))
        assertFalse(order.capabilities.canRequestRefund)
    }

    // ---- promotions ----

    @Test
    fun aPromotionIsAnnouncedThroughEveryChannelTheCustomerOptedInto() {
        app.promoAnnouncer.announce(PromoCode("SAVE10", 10), listOf(Fixtures.customer))

        val log: SentMessageLog = app.sentMessages
        assertEquals(1, log.on(ChannelType.PUSH).size)
        assertEquals(1, log.on(ChannelType.SMS).size)
        assertEquals(1, log.on(ChannelType.EMAIL).size)
        assertTrue(log.all().none { it.urgent })
        assertTrue(log.all().all { it.body.contains("SAVE10") })
    }

    // ---- urgency is data of the status, not a branch on it ----

    @Test
    fun onlyCancellationIsUrgent() {
        assertEquals(listOf(CANCELLED), OrderStatus.values().filter { it.urgent })
        val id = app.place().orderId
        app.lifecycle.confirm(id)
        assertEquals(0, app.sentMessages.all().count { it.urgent })
        app.facade.cancelOrder(id)
        assertEquals(3, app.sentMessages.all().count { it.urgent })
    }

    @Test
    fun pickupAndScheduledOrdersExposeTheSameCapabilitiesApi() {
        val pickup = orderIn(app, CONFIRMED, type = OrderType.PICKUP)
        assertEquals(10, pickup.capabilities.cancellationFeePercent)
        assertEquals(CREATED, orderIn(app, CREATED, type = OrderType.SCHEDULED).status)
    }
}
