package com.nemis.foodhub.ui

import androidx.lifecycle.ViewModel
import com.nemis.foodhub.app.AppContainer
import com.nemis.foodhub.domain.OrderNotFoundException
import com.nemis.foodhub.domain.model.Customer
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.presentation.BaseViewModel
import com.nemis.foodhub.presentation.CartViewModel
import com.nemis.foodhub.presentation.CheckoutViewModel
import com.nemis.foodhub.presentation.InvoiceViewModel
import com.nemis.foodhub.presentation.MenuViewModel
import com.nemis.foodhub.presentation.NotificationLogViewModel
import com.nemis.foodhub.presentation.OrderTrackingViewModel
import com.nemis.foodhub.presentation.SimulatorViewModel

/*
 * The ViewModels live in the pure-JVM module :core (BaseViewModel, StateFlow) so they are unit-tested without
 * Android. These classes adapt them to androidx.lifecycle.ViewModel: they survive configuration changes and
 * forward the framework's onCleared() to BaseViewModel.onCleared(), so subscriptions never outlive the screen.
 * They contain no UI state and no business rules of their own; they are the only Android classes that touch
 * the composition root.
 */

/** ViewModels of the ordering screen; they live as long as the activity (cart survives rotation). */
class OrderingViewModels(container: AppContainer, customer: Customer) : ViewModel() {
    private val editor = container.newCartEditor(customer.id) // shared: checkout empties the cart the cart screen shows

    val menu: MenuViewModel = container.newMenuViewModel()
    val cart: CartViewModel = container.newCartViewModel(editor)
    val checkout: CheckoutViewModel = container.newCheckoutViewModel(editor, customer)

    override fun onCleared() {
        listOf<BaseViewModel>(menu, cart, checkout).forEach { it.onCleared() }
    }
}

/** The ViewModels of one open order. */
class OrderScreen(
    val orderId: OrderId,
    val tracking: OrderTrackingViewModel,
    val invoice: InvoiceViewModel,
    val simulator: SimulatorViewModel,
    val notifications: NotificationLogViewModel
) {
    internal fun clear() = listOf<BaseViewModel>(tracking, invoice, simulator, notifications).forEach { it.onCleared() }
}

/**
 * ViewModels of the order screen, bound to one order at a time. Opening another order or leaving the screen
 * clears the previous ones, which cancels the Observer subscriptions they hold.
 */
class OrderScreenViewModels(private val container: AppContainer, private val customer: Customer) : ViewModel() {

    private var bound: OrderScreen? = null

    /** Null when the order does not exist (orders are in memory, so they are gone after the process was killed). */
    fun bind(orderId: OrderId): OrderScreen? {
        bound?.takeIf { it.orderId == orderId }?.let { return it }
        release()
        val tracking = try {
            container.newOrderTrackingViewModel(orderId)
        } catch (e: OrderNotFoundException) {
            return null
        }
        return OrderScreen(
            orderId,
            tracking,
            container.newInvoiceViewModel(orderId),
            container.newSimulatorViewModel(orderId, listOf(customer)),
            container.newNotificationLogViewModel()
        ).also { bound = it }
    }

    fun release() {
        bound?.clear()
        bound = null
    }

    override fun onCleared() = release()
}
