package com.nemis.foodhub

import com.nemis.foodhub.domain.cart.CartEditor
import com.nemis.foodhub.domain.facade.OrderFacade
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderStatus
import com.nemis.foodhub.domain.repository.MenuRepository
import com.nemis.foodhub.presentation.BaseViewModel
import com.nemis.foodhub.presentation.CartViewModel
import com.nemis.foodhub.presentation.CheckoutUiState
import com.nemis.foodhub.presentation.CheckoutViewModel
import com.nemis.foodhub.presentation.InvoiceViewModel
import com.nemis.foodhub.presentation.MenuViewModel
import com.nemis.foodhub.presentation.OrderTrackingViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/** MVVM: ViewModels expose immutable UI state, hold no View/Context and own no business rules. */
class ViewModelTest {

    private val app = Fixtures.newApp()

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

    @Test
    fun checkoutViewModelReportsSuccessAndFailure() {
        val vm = app.newCheckoutViewModel()
        assertTrue(vm.uiState.value is CheckoutUiState.Idle)

        vm.submit(Fixtures.vnRequest(app))
        val success = vm.uiState.value as CheckoutUiState.Success
        assertEquals(DEFAULT_VN_TOTAL, success.confirmation.price.total)

        vm.submit(Fixtures.vnRequest(app, address = null))
        assertTrue(vm.uiState.value is CheckoutUiState.Failed)
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
        vm.restoreDraft()
        vm.removeItem("pho-bo")
        assertEquals(0, vm.items.size)
    }

    // ---- structure: no View/Context, only abstractions ----

    @Test
    fun viewModelsDependOnlyOnDomainAbstractions() {
        val allowed = setOf<Class<*>>(OrderFacade::class.java, CartEditor::class.java, MenuRepository::class.java, OrderId::class.java, String::class.java)
        val viewModels = listOf(
            CartViewModel::class.java, CheckoutViewModel::class.java, OrderTrackingViewModel::class.java,
            MenuViewModel::class.java, InvoiceViewModel::class.java
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
