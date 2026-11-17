package com.nemis.foodhub.app

import com.nemis.foodhub.data.InMemoryCartDraftRepository
import com.nemis.foodhub.data.InMemoryMenuRepository
import com.nemis.foodhub.data.InMemoryOrderRepository
import com.nemis.foodhub.data.InMemoryPromoCatalog
import com.nemis.foodhub.data.SequentialIdGenerator
import com.nemis.foodhub.data.SimulatedInventoryService
import com.nemis.foodhub.data.channel.EmailChannel
import com.nemis.foodhub.data.channel.PushChannel
import com.nemis.foodhub.data.channel.SentMessageLog
import com.nemis.foodhub.data.channel.SmsChannel
import com.nemis.foodhub.domain.cart.Cart
import com.nemis.foodhub.domain.cart.CartEditor
import com.nemis.foodhub.domain.cart.CartHistory
import com.nemis.foodhub.domain.checkout.CheckoutServices
import com.nemis.foodhub.domain.checkout.DeliveryCheckout
import com.nemis.foodhub.domain.checkout.PickupCheckout
import com.nemis.foodhub.domain.checkout.ScheduledCheckout
import com.nemis.foodhub.domain.facade.DefaultOrderFacade
import com.nemis.foodhub.domain.facade.OrderFacade
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderType
import com.nemis.foodhub.domain.notification.MultiChannelNotifier
import com.nemis.foodhub.domain.notification.NotificationOrderListener
import com.nemis.foodhub.domain.notification.PromoAnnouncer
import com.nemis.foodhub.domain.order.OrderEventPublisher
import com.nemis.foodhub.domain.order.OrderStatusCounter
import com.nemis.foodhub.domain.order.SynchronousOrderEventPublisher
import com.nemis.foodhub.domain.pricing.PricingService
import com.nemis.foodhub.domain.region.RegionFactory
import com.nemis.foodhub.domain.region.RegionRegistry
import com.nemis.foodhub.domain.region.us.UsRegionFactory
import com.nemis.foodhub.domain.region.vn.VietnamRegionFactory
import com.nemis.foodhub.domain.usecase.CancelOrderUseCase
import com.nemis.foodhub.domain.usecase.EditOrderItemsUseCase
import com.nemis.foodhub.domain.usecase.ExportInvoiceUseCase
import com.nemis.foodhub.domain.usecase.OrderLifecycleService
import com.nemis.foodhub.domain.usecase.RefundExecutor
import com.nemis.foodhub.domain.usecase.RefundOrderUseCase
import com.nemis.foodhub.presentation.CartViewModel
import com.nemis.foodhub.presentation.CheckoutViewModel
import com.nemis.foodhub.presentation.InvoiceViewModel
import com.nemis.foodhub.presentation.MenuViewModel
import com.nemis.foodhub.presentation.OrderTrackingViewModel
import java.time.Clock

/**
 * Composition root: the ONLY place where concrete classes are chosen and wired (constructor injection).
 * No singletons or service locators; tests build their own container with a fixed clock or extra regions.
 */
class AppContainer(
    val clock: Clock = Clock.systemUTC(),
    regionFactories: List<RegionFactory> = listOf(VietnamRegionFactory(), UsRegionFactory()),
    initialStock: Map<String, Int> = mapOf("pho-bo" to 50, "ca-phe-sua" to 50, "cheeseburger" to 50, "fries" to 50),
    private val cartHistoryLimit: Int = 10
) {
    val sentMessages = SentMessageLog()
    val statusCounter = OrderStatusCounter()
    val inventory = SimulatedInventoryService(initialStock)
    val orders = InMemoryOrderRepository()
    val menu = InMemoryMenuRepository()
    val drafts = InMemoryCartDraftRepository()
    val events: OrderEventPublisher = SynchronousOrderEventPublisher()

    private val regions = RegionRegistry(regionFactories)
    private val notifier = MultiChannelNotifier(
        listOf(PushChannel(sentMessages), SmsChannel(sentMessages), EmailChannel(sentMessages))
    )
    private val pricing = PricingService()
    private val refunds = RefundExecutor(regions)

    val services = CheckoutServices(
        regions, pricing, InMemoryPromoCatalog(), inventory, orders, events, notifier, clock, SequentialIdGenerator()
    )

    val facade: OrderFacade = DefaultOrderFacade(
        checkouts = mapOf(
            OrderType.DELIVERY to DeliveryCheckout(services),
            OrderType.PICKUP to PickupCheckout(services),
            OrderType.SCHEDULED to ScheduledCheckout(services)
        ),
        cancelOrder = CancelOrderUseCase(orders, refunds),
        refundOrder = RefundOrderUseCase(orders, refunds),
        editOrder = EditOrderItemsUseCase(orders, pricing, regions),
        invoices = ExportInvoiceUseCase(orders, regions, clock),
        orders = orders,
        events = events
    )

    val lifecycle = OrderLifecycleService(orders)
    val promoAnnouncer = PromoAnnouncer(notifier)

    init {
        // Observers are registered once, here. The order and the publisher know none of them.
        events.subscribe(NotificationOrderListener(notifier))
        events.subscribe(statusCounter)
    }

    fun newCartEditor(customerId: String) =
        CartEditor(Cart(clock), CartHistory(cartHistoryLimit), drafts, customerId)

    fun newMenuViewModel() = MenuViewModel(menu)
    fun newInvoiceViewModel(orderId: OrderId) = InvoiceViewModel(facade, orderId)
    fun newCartViewModel(editor: CartEditor) = CartViewModel(editor, menu)
    fun newCheckoutViewModel() = CheckoutViewModel(facade)
    fun newOrderTrackingViewModel(orderId: OrderId) = OrderTrackingViewModel(facade, orderId)
}
