package com.nemis.foodhub.presentation

import com.nemis.foodhub.domain.DomainException
import com.nemis.foodhub.domain.ValidationException
import com.nemis.foodhub.domain.checkout.CheckoutRequest
import com.nemis.foodhub.domain.checkout.OrderConfirmation
import com.nemis.foodhub.domain.cart.CartEditor
import com.nemis.foodhub.domain.facade.OrderFacade
import com.nemis.foodhub.domain.facade.OrderSummary
import com.nemis.foodhub.domain.invoice.InvoiceFormat
import com.nemis.foodhub.domain.model.CartItem
import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderStatus
import com.nemis.foodhub.domain.order.OrderStatusChanged
import com.nemis.foodhub.domain.order.Subscription
import com.nemis.foodhub.domain.repository.MenuRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Minimal base so the module stays pure JVM. On Android each of these extends androidx.lifecycle.ViewModel
 * and [onCleared] is the framework callback. ViewModels hold no View or Context and expose StateFlow only.
 */
abstract class BaseViewModel {
    open fun onCleared() {}
}

// ---------------------------------------------------------------- menu

data class MenuItemUi(val id: String, val name: String, val price: Money)
data class MenuUiState(
    val restaurantIds: List<String> = emptyList(),
    val selectedRestaurantId: String? = null,
    val items: List<MenuItemUi> = emptyList()
)

/** Browse the menu: pick a restaurant, see its items. Adding to the cart is [CartViewModel]'s job. */
class MenuViewModel(private val menu: MenuRepository) : BaseViewModel() {

    private val _state = MutableStateFlow(MenuUiState(restaurantIds = menu.restaurantIds()))
    val uiState: StateFlow<MenuUiState> = _state.asStateFlow()

    fun showRestaurant(restaurantId: String) {
        val items = menu.itemsOf(restaurantId).map { MenuItemUi(it.id, it.name, it.price) }
        _state.update { it.copy(selectedRestaurantId = restaurantId, items = items) }
    }
}

// ---------------------------------------------------------------- cart

data class CartLineUi(val itemId: String, val name: String, val quantity: Int, val lineTotal: Money)
data class CartUiState(val lines: List<CartLineUi> = emptyList(), val canUndo: Boolean = false, val error: String? = null)

class CartViewModel(private val editor: CartEditor, private val menu: MenuRepository) : BaseViewModel() {

    private val _state = MutableStateFlow(snapshot())
    val uiState: StateFlow<CartUiState> = _state.asStateFlow()

    fun addItem(itemId: String, quantity: Int = 1) = run {
        val item = menu.findItem(itemId) ?: throw ValidationException("Unknown menu item $itemId")
        editor.add(item, quantity)
    }

    fun removeItem(itemId: String) = run { editor.remove(itemId) }
    fun setQuantity(itemId: String, quantity: Int) = run { editor.setQuantity(itemId, quantity) }
    fun undo() = run { editor.undo() }
    fun restoreDraft() = run { editor.restoreDraft() }

    val items: List<CartItem> get() = editor.cart.items

    private fun run(action: () -> Unit) {
        try {
            action()
            _state.value = snapshot()
        } catch (e: DomainException) {
            _state.update { it.copy(error = e.message ?: "Cart error") }
        }
    }

    private fun snapshot() = CartUiState(
        lines = editor.cart.items.map { CartLineUi(it.item.id, it.item.name, it.quantity, it.lineTotal) },
        canUndo = editor.canUndo
    )
}

// ---------------------------------------------------------------- checkout

sealed interface CheckoutUiState {
    object Idle : CheckoutUiState
    data class Success(val confirmation: OrderConfirmation) : CheckoutUiState
    data class Failed(val message: String) : CheckoutUiState
}

class CheckoutViewModel(private val facade: OrderFacade) : BaseViewModel() {

    private val _state = MutableStateFlow<CheckoutUiState>(CheckoutUiState.Idle)
    val uiState: StateFlow<CheckoutUiState> = _state.asStateFlow()

    fun submit(request: CheckoutRequest) {
        _state.value = try {
            CheckoutUiState.Success(facade.checkout(request))
        } catch (e: DomainException) {
            CheckoutUiState.Failed(e.message ?: "Checkout failed")
        }
    }
}

// ---------------------------------------------------------------- invoice

sealed interface InvoiceUiState {
    object Idle : InvoiceUiState
    data class Ready(val format: InvoiceFormat, val content: String) : InvoiceUiState
    data class Failed(val message: String) : InvoiceUiState
}

class InvoiceViewModel(private val facade: OrderFacade, private val orderId: OrderId) : BaseViewModel() {

    private val _state = MutableStateFlow<InvoiceUiState>(InvoiceUiState.Idle)
    val uiState: StateFlow<InvoiceUiState> = _state.asStateFlow()

    fun export(format: InvoiceFormat) {
        _state.value = try {
            InvoiceUiState.Ready(format, facade.exportInvoice(orderId, format))
        } catch (e: DomainException) {
            InvoiceUiState.Failed(e.message ?: "Invoice export failed")
        }
    }
}

// ---------------------------------------------------------------- tracking

data class OrderTrackingUiState(
    val status: OrderStatus,
    val message: String,
    val canEditItems: Boolean,
    val canCancel: Boolean,
    val cancellationFeePercent: Int,
    val canRequestRefund: Boolean,
    val canRate: Boolean,
    val canTrackCourier: Boolean,
    val error: String? = null
) {
    companion object {
        fun from(summary: OrderSummary) = of(summary.status, summary.capabilities)
        fun from(event: OrderStatusChanged) = of(event.current, event.capabilities)

        private fun of(status: OrderStatus, c: com.nemis.foodhub.domain.order.OrderCapabilities) = OrderTrackingUiState(
            status, status.customerMessage, c.canEditItems, c.canCancel, c.cancellationFeePercent,
            c.canRequestRefund, c.canRate, c.canTrackCourier
        )
    }
}

/**
 * Observes one order through the domain's Observer. Events may arrive on any thread; writing to a StateFlow is
 * thread-safe and the View collects on its own (main) thread. The subscription is cancelled in [onCleared],
 * so a destroyed screen never leaks a listener.
 */
class OrderTrackingViewModel(private val facade: OrderFacade, private val orderId: OrderId) : BaseViewModel() {

    private val _state = MutableStateFlow(OrderTrackingUiState.from(facade.getOrder(orderId)))
    val uiState: StateFlow<OrderTrackingUiState> = _state.asStateFlow()

    private val subscription: Subscription = facade.track(orderId) { event ->
        _state.value = OrderTrackingUiState.from(event)
    }

    fun cancel() = guarded { facade.cancelOrder(orderId) }
    fun requestRefund() = guarded { facade.requestRefund(orderId) }
    fun rate(stars: Int) = guarded { facade.rateOrder(orderId, stars) }
    fun editItems(newItems: List<CartItem>) = guarded { facade.editOrderItems(orderId, newItems) }

    /**
     * Actions that change the order without changing its status (edit, refund, rate) publish no event, so after
     * a successful action the state is re-read from the domain; the UI still never computes the rules itself.
     */
    private fun guarded(action: () -> Unit) {
        try {
            action()
            _state.value = OrderTrackingUiState.from(facade.getOrder(orderId))
        } catch (e: DomainException) {
            _state.update { it.copy(error = e.message) }
        }
    }

    override fun onCleared() = subscription.cancel()
}
