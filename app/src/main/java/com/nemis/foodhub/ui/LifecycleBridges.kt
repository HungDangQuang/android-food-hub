package com.nemis.foodhub.ui

import androidx.lifecycle.ViewModel
import com.nemis.foodhub.app.AppContainer
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.presentation.BaseViewModel
import com.nemis.foodhub.presentation.CartViewModel
import com.nemis.foodhub.presentation.CheckoutViewModel
import com.nemis.foodhub.presentation.InvoiceViewModel
import com.nemis.foodhub.presentation.MenuViewModel
import com.nemis.foodhub.presentation.OrderTrackingViewModel

/*
 * The ViewModels live in the pure-JVM module :core (BaseViewModel, StateFlow) so they are unit-tested without
 * Android. These classes adapt them to androidx.lifecycle.ViewModel: they survive configuration changes and
 * forward the framework's onCleared() to BaseViewModel.onCleared(), so subscriptions never outlive the screen.
 * They contain no UI state and no business rules of their own.
 */

/** ViewModels of the ordering screen; they live as long as the activity (cart survives rotation). */
class OrderingViewModels(container: AppContainer, customerId: String) : ViewModel() {
    val menu: MenuViewModel = container.newMenuViewModel()
    val cart: CartViewModel = container.newCartViewModel(container.newCartEditor(customerId))
    val checkout: CheckoutViewModel = container.newCheckoutViewModel()

    override fun onCleared() {
        listOf<BaseViewModel>(menu, cart, checkout).forEach { it.onCleared() }
    }
}

/**
 * ViewModels of the order screen, bound to one order at a time. Opening another order or leaving the screen
 * clears the previous ones, which cancels the Observer subscription of [OrderTrackingViewModel].
 */
class OrderScreenViewModels(private val container: AppContainer) : ViewModel() {

    private var boundTo: OrderId? = null
    private var tracking: OrderTrackingViewModel? = null
    private var invoice: InvoiceViewModel? = null

    fun bind(orderId: OrderId): Pair<OrderTrackingViewModel, InvoiceViewModel> {
        if (boundTo != orderId) {
            release()
            boundTo = orderId
            tracking = container.newOrderTrackingViewModel(orderId)
            invoice = container.newInvoiceViewModel(orderId)
        }
        return tracking!! to invoice!!
    }

    fun release() {
        tracking?.onCleared()
        invoice?.onCleared()
        tracking = null
        invoice = null
        boundTo = null
    }

    override fun onCleared() = release()
}
