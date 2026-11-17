package com.nemis.foodhub.domain.facade

import com.nemis.foodhub.domain.ValidationException
import com.nemis.foodhub.domain.invoice.InvoiceFormat
import com.nemis.foodhub.domain.checkout.CheckoutProcess
import com.nemis.foodhub.domain.checkout.CheckoutRequest
import com.nemis.foodhub.domain.checkout.OrderConfirmation
import com.nemis.foodhub.domain.model.CartItem
import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderStatus
import com.nemis.foodhub.domain.model.OrderType
import com.nemis.foodhub.domain.order.CancellationResult
import com.nemis.foodhub.domain.order.OrderCapabilities
import com.nemis.foodhub.domain.order.OrderEventPublisher
import com.nemis.foodhub.domain.order.OrderStatusListener
import com.nemis.foodhub.domain.order.Subscription
import com.nemis.foodhub.domain.pricing.PriceBreakdown
import com.nemis.foodhub.domain.repository.OrderRepository
import com.nemis.foodhub.domain.repository.getOrThrow
import com.nemis.foodhub.domain.usecase.CancelOrderUseCase
import com.nemis.foodhub.domain.usecase.EditOrderItemsUseCase
import com.nemis.foodhub.domain.usecase.ExportInvoiceUseCase
import com.nemis.foodhub.domain.usecase.RefundOrderUseCase

data class OrderSummary(
    val id: OrderId,
    val type: OrderType,
    val status: OrderStatus,
    val capabilities: OrderCapabilities,
    val items: List<CartItem>,
    val price: PriceBreakdown
)

/**
 * Facade (C7): the only domain entry point a ViewModel needs. It does not know Inventory, Pricing, Payment or
 * Notification; those stay behind the checkout flows and use cases.
 */
interface OrderFacade {
    fun checkout(request: CheckoutRequest): OrderConfirmation
    fun cancelOrder(orderId: OrderId): CancellationResult
    fun requestRefund(orderId: OrderId): Money
    fun editOrderItems(orderId: OrderId, newItems: List<CartItem>)
    fun rateOrder(orderId: OrderId, stars: Int)
    fun getOrder(orderId: OrderId): OrderSummary
    fun exportInvoice(orderId: OrderId, format: InvoiceFormat): String
    fun track(orderId: OrderId, listener: OrderStatusListener): Subscription
}

/** Pure delegation: routes each call to the right flow or use case and holds no business rules. */
class DefaultOrderFacade(
    private val checkouts: Map<OrderType, CheckoutProcess>,
    private val cancelOrder: CancelOrderUseCase,
    private val refundOrder: RefundOrderUseCase,
    private val editOrder: EditOrderItemsUseCase,
    private val invoices: ExportInvoiceUseCase,
    private val orders: OrderRepository,
    private val events: OrderEventPublisher
) : OrderFacade {

    override fun checkout(request: CheckoutRequest): OrderConfirmation =
        (checkouts[request.type] ?: throw ValidationException("Unsupported order type ${request.type}"))
            .checkout(request)

    override fun cancelOrder(orderId: OrderId) = cancelOrder.execute(orderId)

    override fun requestRefund(orderId: OrderId) = refundOrder.execute(orderId)

    override fun editOrderItems(orderId: OrderId, newItems: List<CartItem>) = editOrder.execute(orderId, newItems)

    override fun rateOrder(orderId: OrderId, stars: Int) {
        val order = orders.getOrThrow(orderId)
        order.rate(stars)
        orders.save(order)
    }

    override fun getOrder(orderId: OrderId): OrderSummary {
        val order = orders.getOrThrow(orderId)
        return OrderSummary(order.id, order.type, order.status, order.capabilities, order.items, order.price)
    }

    override fun exportInvoice(orderId: OrderId, format: InvoiceFormat) = invoices.execute(orderId, format)

    override fun track(orderId: OrderId, listener: OrderStatusListener): Subscription =
        events.subscribe(listener, orderId)
}
