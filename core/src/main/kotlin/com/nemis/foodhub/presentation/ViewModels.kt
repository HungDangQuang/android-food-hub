package com.nemis.foodhub.presentation

import com.nemis.foodhub.domain.DomainException
import com.nemis.foodhub.domain.Subscription
import com.nemis.foodhub.domain.ValidationException
import com.nemis.foodhub.domain.cart.CartEditor
import com.nemis.foodhub.domain.checkout.CheckoutRequest
import com.nemis.foodhub.domain.checkout.OrderConfirmation
import com.nemis.foodhub.domain.facade.OrderFacade
import com.nemis.foodhub.domain.facade.OrderSummary
import com.nemis.foodhub.domain.invoice.InvoiceFormat
import com.nemis.foodhub.domain.model.Address
import com.nemis.foodhub.domain.model.CartItem
import com.nemis.foodhub.domain.model.Customer
import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderStatus
import com.nemis.foodhub.domain.model.OrderType
import com.nemis.foodhub.domain.notification.NotificationOutbox
import com.nemis.foodhub.domain.notification.PromoAnnouncer
import com.nemis.foodhub.domain.notification.SentNotification
import com.nemis.foodhub.domain.order.OrderCapabilities
import com.nemis.foodhub.domain.order.OrderStatusChanged
import com.nemis.foodhub.domain.payment.PaymentMethod
import com.nemis.foodhub.domain.payment.SimulatedPaymentProcessor
import com.nemis.foodhub.domain.pricing.PromoCatalog
import com.nemis.foodhub.domain.repository.MenuRepository
import com.nemis.foodhub.domain.usecase.OrderLifecycleService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.Duration

/**
 * Minimal base so the module stays pure JVM. On Android each of these is wrapped by an androidx.lifecycle.ViewModel
 * whose onCleared() calls [onCleared]. ViewModels hold no View or Context and expose StateFlow only.
 */
abstract class BaseViewModel {
    open fun onCleared() {}
}

// ---------------------------------------------------------------- menu

data class MenuItemUi(val id: String, val name: String, val price: Money)
data class RestaurantUi(val id: String, val regionCode: String?)
data class MenuUiState(
    val restaurants: List<RestaurantUi> = emptyList(),
    val selectedRestaurantId: String? = null,
    val items: List<MenuItemUi> = emptyList()
) {
    val restaurantIds: List<String> get() = restaurants.map { it.id }
}

/** Browse the menu: pick a restaurant, see its items. Adding to the cart is [CartViewModel]'s job. */
class MenuViewModel(private val menu: MenuRepository) : BaseViewModel() {

    private val _state = MutableStateFlow(
        MenuUiState(restaurants = menu.restaurantIds().map { RestaurantUi(it, menu.regionOf(it)) })
    )
    val uiState: StateFlow<MenuUiState> = _state.asStateFlow()

    fun showRestaurant(restaurantId: String) {
        val items = menu.itemsOf(restaurantId).map { MenuItemUi(it.id, it.name, it.price) }
        _state.update { it.copy(selectedRestaurantId = restaurantId, items = items) }
    }
}

// ---------------------------------------------------------------- cart

data class CartLineUi(val itemId: String, val name: String, val quantity: Int, val lineTotal: Money)
data class CartUiState(
    val lines: List<CartLineUi> = emptyList(),
    val canUndo: Boolean = false,
    val canRestoreDraft: Boolean = false,
    val error: String? = null
)

/**
 * The cart screen. It observes the shared [CartEditor], so a change made elsewhere (checkout empties the cart)
 * shows up here too; the subscription is cancelled in [onCleared].
 */
class CartViewModel(private val editor: CartEditor, private val menu: MenuRepository) : BaseViewModel() {

    private val _state = MutableStateFlow(snapshot())
    val uiState: StateFlow<CartUiState> = _state.asStateFlow()

    private val subscription: Subscription = editor.observe { _state.value = snapshot() }

    fun addItem(itemId: String, quantity: Int = 1) = run {
        val item = menu.findItem(itemId) ?: throw ValidationException("Unknown menu item $itemId")
        editor.add(item, quantity)
    }

    fun removeItem(itemId: String) = run { editor.remove(itemId) }
    fun setQuantity(itemId: String, quantity: Int) = run { editor.setQuantity(itemId, quantity) }
    fun undo() = run { editor.undo() }
    fun restoreDraft() = run { editor.restoreDraft() }

    private fun run(action: () -> Unit) {
        try {
            action()
        } catch (e: DomainException) {
            _state.update { it.copy(error = e.message ?: "Cart error") }
        }
    }

    /** A draft is only worth offering on an empty cart: otherwise it equals the cart itself. */
    private fun snapshot() = CartUiState(
        lines = editor.cart.items.map { CartLineUi(it.item.id, it.item.name, it.quantity, it.lineTotal) },
        canUndo = editor.canUndo,
        canRestoreDraft = editor.cart.isEmpty && editor.hasDraft
    )

    override fun onCleared() = subscription.cancel()
}

// ---------------------------------------------------------------- checkout

/** Raw form input. [scheduleIn] is only used for scheduled orders, [deliveryAddress] only when a courier comes. */
data class CheckoutForm(
    val type: OrderType,
    val paymentMethod: PaymentMethod,
    val payerRef: String,
    val promoCode: String = "",
    val deliveryAddress: String = "",
    val scheduleIn: Duration? = null
)

sealed interface CheckoutUiState {
    object Idle : CheckoutUiState
    data class Success(val confirmation: OrderConfirmation) : CheckoutUiState
    data class Failed(val message: String) : CheckoutUiState
}

/**
 * Turns the form and the current cart into a [CheckoutRequest] and calls the facade, its only domain entry point
 * for placing an order. The region comes from the restaurant, the scheduled time from the injected clock.
 * A placed order empties the cart (and its draft); the cart screen follows because it observes the same editor.
 */
class CheckoutViewModel(
    private val facade: OrderFacade,
    private val cart: CartEditor,
    private val menu: MenuRepository,
    private val customer: Customer,
    private val clock: Clock
) : BaseViewModel() {

    private val _state = MutableStateFlow<CheckoutUiState>(CheckoutUiState.Idle)
    val uiState: StateFlow<CheckoutUiState> = _state.asStateFlow()

    /** Payer reference that makes the simulated gateway decline, shown as a hint on the demo screen. */
    val declinedPayerRef: String = SimulatedPaymentProcessor.DECLINED_PAYER

    fun submit(form: CheckoutForm) {
        _state.value = try {
            val confirmation = facade.checkout(requestFor(form))
            cart.completeCheckout()
            CheckoutUiState.Success(confirmation)
        } catch (e: DomainException) {
            CheckoutUiState.Failed(e.message ?: "Checkout failed")
        }
    }

    /** The screen has navigated to the placed order: go back to an empty form state. */
    fun acknowledge() {
        _state.value = CheckoutUiState.Idle
    }

    private fun requestFor(form: CheckoutForm): CheckoutRequest {
        val items = cart.cart.items
        val restaurant = items.firstOrNull()?.item?.restaurantId ?: throw ValidationException("Cart is empty")
        val region = menu.regionOf(restaurant) ?: throw ValidationException("Restaurant $restaurant has no region")
        return CheckoutRequest(
            customer = customer,
            items = items,
            type = form.type,
            regionCode = region,
            paymentMethod = form.paymentMethod,
            payerRef = form.payerRef,
            promoCode = form.promoCode.trim().ifEmpty { null },
            deliveryAddress = if (form.type.requiresCourier) Address(form.deliveryAddress) else null,
            scheduledAt = form.scheduleIn?.let { clock.instant().plus(it) }
        )
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

data class OrderLineUi(val itemId: String, val name: String, val quantity: Int, val lineTotal: Money)

/** Outcome of the customer's last successful action, with the amounts the domain decided. */
sealed interface OrderNotice {
    data class Cancelled(val fee: Money, val refund: Money) : OrderNotice
    data class RefundRequested(val amount: Money) : OrderNotice
    data class ItemsUpdated(val total: Money) : OrderNotice
    data class Rated(val stars: Int) : OrderNotice
}

data class OrderTrackingUiState(
    val status: OrderStatus,
    val message: String,
    val canEditItems: Boolean,
    val canCancel: Boolean,
    val cancellationFeePercent: Int,
    val canRequestRefund: Boolean,
    val canRate: Boolean,
    val canTrackCourier: Boolean,
    val lines: List<OrderLineUi> = emptyList(),
    val total: Money? = null,
    val notice: OrderNotice? = null,
    val error: String? = null,
    /** Number of status transitions this state reflects; an older read or event never replaces a newer one. */
    val version: Int = -1
) {
    fun withStatus(status: OrderStatus, c: OrderCapabilities, version: Int) = copy(
        status = status, message = status.customerMessage, canEditItems = c.canEditItems, canCancel = c.canCancel,
        cancellationFeePercent = c.cancellationFeePercent, canRequestRefund = c.canRequestRefund, canRate = c.canRate,
        canTrackCourier = c.canTrackCourier, version = version
    )

    companion object {
        /** Placeholder until the first read; replaced before the constructor of the ViewModel returns. */
        internal val NOT_LOADED = OrderTrackingUiState(
            OrderStatus.CREATED, "", false, false, 0, false, false, false
        )
    }
}

/**
 * Observes one order through the domain's Observer. It subscribes BEFORE reading the order, so a transition that
 * happens while the screen opens is never lost; versions decide which of the read and the event is newer.
 * Events may arrive on any thread: StateFlow updates are thread-safe and the View collects on the main thread.
 * The subscription is cancelled in [onCleared], so a destroyed screen never leaks a listener.
 */
class OrderTrackingViewModel(private val facade: OrderFacade, private val orderId: OrderId) : BaseViewModel() {

    private val _state = MutableStateFlow(OrderTrackingUiState.NOT_LOADED)
    val uiState: StateFlow<OrderTrackingUiState> = _state.asStateFlow()

    @Volatile
    private var items: List<CartItem> = emptyList()

    private val subscription: Subscription = facade.track(orderId) { event -> applyEvent(event) }

    init {
        try {
            applySummary(facade.getOrder(orderId))
        } catch (e: DomainException) {
            subscription.cancel()
            throw e
        }
    }

    fun cancel() = guarded { facade.cancelOrder(orderId).let { OrderNotice.Cancelled(it.fee, it.refund) } }
    fun requestRefund() = guarded { OrderNotice.RefundRequested(facade.requestRefund(orderId)) }
    fun rate(stars: Int) = guarded { facade.rateOrder(orderId, stars); OrderNotice.Rated(stars) }

    fun editItems(newItems: List<CartItem>) = guarded {
        facade.editOrderItems(orderId, newItems)
        OrderNotice.ItemsUpdated(facade.getOrder(orderId).price.total)
    }

    /** Changes one line of the order; 0 removes the line. Whether editing is allowed is decided by the order. */
    fun setItemQuantity(itemId: String, quantity: Int) = editItems(
        items.mapNotNull { line ->
            when {
                line.item.id != itemId -> line
                quantity > 0 -> CartItem(line.item, quantity)
                else -> null
            }
        }
    )

    /**
     * Actions that change the order without changing its status (edit, refund, rate) publish no event, so after
     * a successful action the order is read again; the UI still never computes the rules itself.
     */
    private fun guarded(action: () -> OrderNotice) {
        try {
            val notice = action()
            applySummary(facade.getOrder(orderId))
            _state.update { it.copy(notice = notice, error = null) }
        } catch (e: DomainException) {
            _state.update { it.copy(error = e.message, notice = null) }
        }
    }

    private fun applyEvent(event: OrderStatusChanged) = _state.update {
        if (event.version >= it.version) it.withStatus(event.current, event.capabilities, event.version) else it
    }

    private fun applySummary(summary: OrderSummary) {
        items = summary.items
        _state.update {
            val withLines = it.copy(
                lines = summary.items.map { line -> OrderLineUi(line.item.id, line.item.name, line.quantity, line.lineTotal) },
                total = summary.price.total
            )
            if (summary.version >= it.version) withLines.withStatus(summary.status, summary.capabilities, summary.version) else withLines
        }
    }

    override fun onCleared() = subscription.cancel()
}

// ---------------------------------------------------------------- simulated restaurant / courier / admin

data class SimulatorUiState(val error: String? = null)

/**
 * The Restaurant, Courier and Admin apps are out of scope; the demo screen drives their side of the order through
 * this ViewModel, so the View still only forwards clicks and the domain decides what is allowed.
 */
class SimulatorViewModel(
    private val lifecycle: OrderLifecycleService,
    private val announcer: PromoAnnouncer,
    private val promos: PromoCatalog,
    private val orderId: OrderId,
    private val audience: List<Customer>
) : BaseViewModel() {

    private val _state = MutableStateFlow(SimulatorUiState())
    val uiState: StateFlow<SimulatorUiState> = _state.asStateFlow()

    fun confirm() = attempt { lifecycle.confirm(orderId) }
    fun startPreparing() = attempt { lifecycle.startPreparing(orderId) }
    fun startDelivery() = attempt { lifecycle.startDelivery(orderId) }
    fun markDelivered() = attempt { lifecycle.markDelivered(orderId) }

    fun announcePromo(code: String) = attempt {
        val promo = promos.find(code) ?: throw ValidationException("Unknown promo code '$code'")
        announcer.announce(promo, audience)
    }

    private fun attempt(action: () -> Unit) {
        _state.value = try {
            action()
            SimulatorUiState()
        } catch (e: DomainException) {
            SimulatorUiState(error = e.message)
        }
    }
}

// ---------------------------------------------------------------- notification outbox

data class NotificationLogUiState(val messages: List<SentNotification> = emptyList())

/** The latest messages that left the push/SMS/email channels, refreshed whenever a new one is sent. */
class NotificationLogViewModel(private val outbox: NotificationOutbox, private val limit: Int = 9) : BaseViewModel() {

    private val _state = MutableStateFlow(NotificationLogUiState(outbox.recent(limit)))
    val uiState: StateFlow<NotificationLogUiState> = _state.asStateFlow()

    private val subscription: Subscription = outbox.observe { _state.value = NotificationLogUiState(outbox.recent(limit)) }

    override fun onCleared() = subscription.cancel()
}
