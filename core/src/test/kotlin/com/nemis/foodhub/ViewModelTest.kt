package com.nemis.foodhub

import com.nemis.foodhub.domain.cart.CartEditor
import com.nemis.foodhub.domain.facade.OrderFacade
import com.nemis.foodhub.domain.facade.OrderSummary
import com.nemis.foodhub.domain.model.Currency
import com.nemis.foodhub.domain.model.Customer
import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderStatus
import com.nemis.foodhub.domain.model.OrderType
import com.nemis.foodhub.domain.notification.NotificationOutbox
import com.nemis.foodhub.domain.notification.PromoAnnouncer
import com.nemis.foodhub.domain.payment.PaymentMethod
import com.nemis.foodhub.domain.pricing.PromoCatalog
import com.nemis.foodhub.domain.repository.MenuRepository
import com.nemis.foodhub.domain.usecase.OrderLifecycleService
import com.nemis.foodhub.presentation.BaseViewModel
import com.nemis.foodhub.presentation.CartViewModel
import com.nemis.foodhub.presentation.CheckoutForm
import com.nemis.foodhub.presentation.CheckoutUiState
import com.nemis.foodhub.presentation.CheckoutViewModel
import com.nemis.foodhub.presentation.InvoiceViewModel
import com.nemis.foodhub.presentation.MenuViewModel
import com.nemis.foodhub.presentation.NotificationLogViewModel
import com.nemis.foodhub.presentation.OrderNotice
import com.nemis.foodhub.presentation.OrderTrackingViewModel
import com.nemis.foodhub.presentation.SimulatorViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.Clock
import java.time.Duration

/** MVVM: ViewModels expose immutable UI state, hold no View/Context and own no business rules. */
class ViewModelTest {

    private val clock = MutableClock()
    private val app = Fixtures.newApp(clock)
    private val customer = Fixtures.customer

    // ---- tracking ----

    @Test
    fun trackingStateStartsFromTheOrderAndFollowsDomainEvents() {
        val id = app.place().orderId
        val vm = app.newOrderTrackingViewModel(id)
        assertEquals(OrderStatus.CREATED, vm.uiState.value.status)
        assertTrue(vm.uiState.value.canEditItems)

        app.lifecycle.confirm(id)

        val state = vm.uiState.value
        assertEquals(OrderStatus.CONFIRMED, state.status)
        assertEquals(OrderStatus.CONFIRMED.customerMessage, state.message)
        assertFalse(state.canEditItems)
        assertEquals(10, state.cancellationFeePercent)
        vm.onCleared()
    }

    @Test
    fun anEventThatArrivesWhileTheScreenOpensIsNotLost() {
        val id = app.place().orderId
        val stale = app.facade.getOrder(id)
        // The restaurant confirms exactly while the screen reads the order: the read returns the old status.
        val racing = object : OrderFacade by app.facade {
            override fun getOrder(orderId: OrderId): OrderSummary {
                app.lifecycle.confirm(orderId)
                return stale
            }
        }

        val vm = OrderTrackingViewModel(racing, id)

        assertEquals(OrderStatus.CONFIRMED, vm.uiState.value.status)
        assertEquals(1, vm.uiState.value.lines.size)
        vm.onCleared()
    }

    @Test
    fun courierTrackingAppearsOnlyWhileDelivering() {
        val id = app.place().orderId
        val vm = app.newOrderTrackingViewModel(id)
        app.lifecycle.confirm(id); app.lifecycle.startPreparing(id)
        assertFalse(vm.uiState.value.canTrackCourier)
        app.lifecycle.startDelivery(id)
        assertTrue(vm.uiState.value.canTrackCourier)
        app.lifecycle.markDelivered(id)
        assertFalse(vm.uiState.value.canTrackCourier)
        assertTrue(vm.uiState.value.canRate)
        vm.onCleared()
    }

    @Test
    fun aRejectedActionBecomesAnErrorStateAndNeverCrashes() {
        val id = app.place().orderId
        val vm = app.newOrderTrackingViewModel(id)
        app.lifecycle.confirm(id); app.lifecycle.startPreparing(id)

        vm.cancel()

        assertEquals(OrderStatus.PREPARING, vm.uiState.value.status)
        assertNotNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.notice)
        vm.onCleared()
    }

    @Test
    fun successfulActionClearsTheErrorAndShowsTheNewStatus() {
        val id = app.place().orderId
        val vm = app.newOrderTrackingViewModel(id)
        vm.requestRefund()
        assertNotNull(vm.uiState.value.error)

        vm.cancel()

        assertEquals(OrderStatus.CANCELLED, vm.uiState.value.status)
        assertNull(vm.uiState.value.error)
        vm.onCleared()
    }

    @Test
    fun cancellingShowsTheFeeAndTheRefundDecidedByTheState() {
        val id = app.place().orderId
        app.lifecycle.confirm(id)
        val vm = app.newOrderTrackingViewModel(id)

        vm.cancel()

        val notice = vm.uiState.value.notice as OrderNotice.Cancelled
        assertEquals(Money.of(13_600, Currency.VND), notice.fee)
        assertEquals(Money.of(122_400, Currency.VND), notice.refund)
        vm.onCleared()
    }

    @Test
    fun theOrderScreenShowsItsLinesAndEditsOneQuantityAtATime() {
        val id = app.place().orderId
        val vm = app.newOrderTrackingViewModel(id)
        assertEquals(listOf("pho-bo" to 2), vm.uiState.value.lines.map { it.itemId to it.quantity })
        assertEquals(DEFAULT_VN_TOTAL, vm.uiState.value.total)

        vm.setItemQuantity("pho-bo", 1)

        assertEquals(listOf("pho-bo" to 1), vm.uiState.value.lines.map { it.itemId to it.quantity })
        assertEquals(Money.of(75_500, Currency.VND), vm.uiState.value.total)
        assertTrue(vm.uiState.value.notice is OrderNotice.ItemsUpdated)

        vm.setItemQuantity("pho-bo", 0) // an order cannot become empty
        assertNotNull(vm.uiState.value.error)
        assertEquals(1, vm.uiState.value.lines.single().quantity)
        vm.onCleared()
    }

    @Test
    fun onClearedUnsubscribesSoADestroyedScreenNeverUpdates() {
        val id = app.place().orderId
        val vm = app.newOrderTrackingViewModel(id)
        vm.onCleared()

        app.lifecycle.confirm(id)

        assertEquals(OrderStatus.CREATED, vm.uiState.value.status)
    }

    @Test(timeout = 10_000)
    fun eventsFromABackgroundThreadAreSafelyReflectedInTheState() {
        val id = app.place().orderId
        val vm = app.newOrderTrackingViewModel(id)

        val worker = Thread { app.lifecycle.confirm(id) }
        worker.start(); worker.join()

        assertEquals(OrderStatus.CONFIRMED, vm.uiState.value.status)
        vm.onCleared()
    }

    // ---- checkout ----

    private fun cartWithPho(customerId: String = customer.id) =
        app.newCartEditor(customerId).also { it.add(app.menu.findItem("pho-bo")!!, 2) }

    private val deliveryForm = CheckoutForm(OrderType.DELIVERY, PaymentMethod.CARD, "visa-4242", deliveryAddress = "12 Nguyen Hue")

    @Test
    fun checkoutViewModelBuildsTheRequestFromTheCartAndTheForm() {
        val vm = app.newCheckoutViewModel(cartWithPho(), customer)
        assertTrue(vm.uiState.value is CheckoutUiState.Idle)

        vm.submit(deliveryForm)

        val success = vm.uiState.value as CheckoutUiState.Success
        assertEquals(DEFAULT_VN_TOTAL, success.confirmation.price.total)
        assertEquals("VN", app.order(success.confirmation.orderId).region.code) // region comes from the restaurant
    }

    @Test
    fun scheduledOrdersAreTimedFromTheInjectedClock() {
        val vm = app.newCheckoutViewModel(cartWithPho(), customer)

        vm.submit(deliveryForm.copy(type = OrderType.SCHEDULED, scheduleIn = Duration.ofHours(2)))

        val id = (vm.uiState.value as CheckoutUiState.Success).confirmation.orderId
        assertEquals(clock.instant().plus(Duration.ofHours(2)), app.order(id).scheduledAt)
    }

    @Test
    fun aPlacedOrderEmptiesTheCartAndItsDraftAndTheCartScreenFollows() {
        val editor = app.newCartEditor(customer.id)
        val cartVm = app.newCartViewModel(editor)
        cartVm.addItem("pho-bo", 2)
        val checkoutVm = app.newCheckoutViewModel(editor, customer)

        checkoutVm.submit(deliveryForm)

        assertTrue(checkoutVm.uiState.value is CheckoutUiState.Success)
        assertTrue(cartVm.uiState.value.lines.isEmpty())
        assertFalse(cartVm.uiState.value.canUndo)
        assertFalse(cartVm.uiState.value.canRestoreDraft)
        assertNull(app.drafts.load(customer.id))
        cartVm.onCleared()
    }

    @Test
    fun checkoutFailuresBecomeMessagesAndKeepTheCart() {
        val empty = app.newCheckoutViewModel(app.newCartEditor("c-empty"), customer)
        empty.submit(deliveryForm)
        assertTrue(empty.uiState.value is CheckoutUiState.Failed)

        val editor = cartWithPho()
        val vm = app.newCheckoutViewModel(editor, customer)
        vm.submit(deliveryForm.copy(deliveryAddress = " "))
        assertTrue(vm.uiState.value is CheckoutUiState.Failed)
        assertEquals(1, editor.cart.items.size)

        vm.submit(deliveryForm)
        vm.acknowledge()
        assertTrue("the screen resets once it has navigated to the order", vm.uiState.value is CheckoutUiState.Idle)
    }

    // ---- cart ----

    @Test
    fun cartViewModelShowsLinesUndoAndErrors() {
        val editor = app.newCartEditor("c-9")
        val vm = app.newCartViewModel(editor)
        assertTrue(vm.uiState.value.lines.isEmpty())

        vm.addItem("pho-bo", 2); vm.addItem("ca-phe-sua")
        assertEquals(listOf("Pho bo" to 2, "Ca phe sua da" to 1), vm.uiState.value.lines.map { it.name to it.quantity })
        assertTrue(vm.uiState.value.canUndo)

        vm.undo()
        assertEquals(1, vm.uiState.value.lines.size)

        vm.addItem("does-not-exist")
        assertNotNull(vm.uiState.value.error)

        vm.addItem("cheeseburger") // other restaurant
        assertNotNull(vm.uiState.value.error)

        vm.setQuantity("pho-bo", 0)
        assertTrue(vm.uiState.value.lines.isEmpty())
        assertNull(vm.uiState.value.error)
        assertFalse("an emptied cart leaves no draft", vm.uiState.value.canRestoreDraft)
        vm.onCleared()
    }

    @Test
    fun aDraftLeftByAnEarlierSessionCanBeRestored() {
        cartWithPho("c-7")

        val nextSession = app.newCartViewModel(app.newCartEditor("c-7"))
        assertTrue(nextSession.uiState.value.lines.isEmpty())
        assertTrue(nextSession.uiState.value.canRestoreDraft)

        nextSession.restoreDraft()

        assertEquals(listOf("pho-bo" to 2), nextSession.uiState.value.lines.map { it.itemId to it.quantity })
        assertFalse(nextSession.uiState.value.canRestoreDraft)
        nextSession.onCleared()
    }

    // ---- simulated restaurant, courier and admin; notification outbox ----

    @Test
    fun theSimulatorDrivesTheLifecycleAndReportsRejectedSteps() {
        val id = app.place().orderId
        val tracking = app.newOrderTrackingViewModel(id)
        val sim = app.newSimulatorViewModel(id, listOf(customer))

        sim.startDelivery()
        assertNotNull(sim.uiState.value.error)

        sim.confirm()
        assertNull(sim.uiState.value.error)
        assertEquals(OrderStatus.CONFIRMED, tracking.uiState.value.status)

        sim.startPreparing(); sim.startDelivery(); sim.markDelivered()
        assertEquals(OrderStatus.DELIVERED, tracking.uiState.value.status)

        sim.announcePromo("NOPE")
        assertNotNull(sim.uiState.value.error)
        sim.announcePromo("SAVE10")
        assertNull(sim.uiState.value.error)
        tracking.onCleared()
    }

    @Test
    fun theNotificationLogFollowsTheOutboxUntilCleared() {
        val log = app.newNotificationLogViewModel()
        assertTrue(log.uiState.value.messages.isEmpty())

        val id = app.place().orderId
        assertEquals(3, log.uiState.value.messages.size)

        app.lifecycle.confirm(id)
        assertEquals(6, log.uiState.value.messages.size)
        assertEquals(OrderStatus.CONFIRMED.customerMessage, log.uiState.value.messages.first().body.substringAfter(": "))

        log.onCleared()
        app.lifecycle.startPreparing(id)
        assertEquals(6, log.uiState.value.messages.size)
    }

    // ---- structure: no View/Context, only abstractions ----

    @Test
    fun viewModelsDependOnlyOnDomainTypes() {
        val allowed = setOf<Class<*>>(
            // abstractions
            OrderFacade::class.java, MenuRepository::class.java, NotificationOutbox::class.java, PromoCatalog::class.java,
            Clock::class.java,
            // pure domain services with a single implementation (see DESIGN_RATIONALE 4.5)
            CartEditor::class.java, OrderLifecycleService::class.java, PromoAnnouncer::class.java,
            // values
            OrderId::class.java, Customer::class.java, String::class.java, List::class.java
        )
        val viewModels = listOf(
            CartViewModel::class.java, CheckoutViewModel::class.java, OrderTrackingViewModel::class.java,
            MenuViewModel::class.java, InvoiceViewModel::class.java, SimulatorViewModel::class.java,
            NotificationLogViewModel::class.java
        )
        for (type in viewModels) {
            assertTrue(BaseViewModel::class.java.isAssignableFrom(type))
            for (constructor in type.constructors) {
                constructor.parameterTypes
                    .filterNot { it.isPrimitive || it.name.endsWith("DefaultConstructorMarker") } // synthetic default-arg constructor
                    .forEach { assertTrue("${type.simpleName} depends on ${it.name}", it in allowed) }
            }
            type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach {
                assertFalse("${it.type.name} must not be a view or context", it.type.name.contains("android") || it.type.name.endsWith("View"))
            }
        }
    }
}
