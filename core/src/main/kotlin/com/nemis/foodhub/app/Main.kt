package com.nemis.foodhub.app

import com.nemis.foodhub.data.InMemoryMenuRepository
import com.nemis.foodhub.domain.checkout.CheckoutRequest
import com.nemis.foodhub.domain.invoice.InvoiceFormat
import com.nemis.foodhub.domain.model.Address
import com.nemis.foodhub.domain.model.ChannelType
import com.nemis.foodhub.domain.model.Customer
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderStatus
import com.nemis.foodhub.domain.model.OrderType
import com.nemis.foodhub.domain.payment.PaymentMethod
import com.nemis.foodhub.domain.pricing.PromoCode
import com.nemis.foodhub.presentation.CheckoutUiState
import com.nemis.foodhub.presentation.InvoiceUiState
import com.nemis.foodhub.presentation.OrderTrackingUiState

/**
 * Demo "View": a console that only forwards input to ViewModels and prints their state.
 * Run with: ./gradlew :core:run
 */
fun main() {
    val app = AppContainer()
    val customer = Customer("c-1", "Hung", "+84900000001", "hung@example.com", setOf(ChannelType.PUSH, ChannelType.SMS, ChannelType.EMAIL))

    println("=== 0. Browse the menu ===")
    val menuVm = app.newMenuViewModel()
    menuVm.uiState.value.restaurantIds.forEach { restaurantId ->
        menuVm.showRestaurant(restaurantId)
        println("$restaurantId: ${menuVm.uiState.value.items.map { "${it.name} (${it.price})" }}")
    }

    println("\n=== 1. Cart: add, undo, crash recovery ===")
    val editor = app.newCartEditor(customer.id)
    val cartVm = app.newCartViewModel(editor)
    cartVm.addItem("pho-bo", 2)
    cartVm.addItem("ca-phe-sua")
    println("cart: ${cartVm.uiState.value.lines.map { "${it.name} x${it.quantity}" }}")
    cartVm.undo()
    println("after undo: ${cartVm.uiState.value.lines.map { "${it.name} x${it.quantity}" }}")
    val afterCrash = app.newCartViewModel(app.newCartEditor(customer.id).also { it.restoreDraft() })
    println("restored draft in a fresh session: ${afterCrash.uiState.value.lines.map { "${it.name} x${it.quantity}" }}")

    println("\n=== 2. Checkout (VN, delivery, MoMo wallet, promo SAVE10) ===")
    val checkoutVm = app.newCheckoutViewModel()
    checkoutVm.submit(
        CheckoutRequest(
            customer, cartVm.items, OrderType.DELIVERY, "VN", PaymentMethod.WALLET, "momo-0901",
            promoCode = "SAVE10", deliveryAddress = Address("12 Nguyen Hue, District 1")
        )
    )
    val success = checkoutVm.uiState.value as CheckoutUiState.Success
    val orderId = success.confirmation.orderId
    println("placed ${orderId}: total ${success.confirmation.price.total}, status ${success.confirmation.status}")

    println("\n=== 3. Real-time tracking (Observer) and state-dependent actions (State) ===")
    val trackingVm = app.newOrderTrackingViewModel(orderId)
    fun show(label: String, vm: com.nemis.foodhub.presentation.OrderTrackingViewModel = trackingVm) = vm.uiState.value.let { s: OrderTrackingUiState ->
        println("$label -> ${s.status} | edit=${s.canEditItems} cancel=${s.canCancel}(fee ${s.cancellationFeePercent}%) courier=${s.canTrackCourier} refund=${s.canRequestRefund} rate=${s.canRate}")
    }
    show("initial        ")
    trackingVm.editItems(cartVm.items.map { it.copy(quantity = 3) })
    println("   (items edited while CREATED, error=${trackingVm.uiState.value.error})")
    app.lifecycle.confirm(orderId); show("restaurant ok  ")
    trackingVm.editItems(cartVm.items)
    println("   (edit after CONFIRMED rejected: ${trackingVm.uiState.value.error})")
    trackingVm.cancel()
    println("   (cancel after confirm is allowed with a 10% fee: now ${trackingVm.uiState.value.status})")

    println("\n=== 4. Second order: full lifecycle, then refund and rating ===")
    val second = app.newCheckoutViewModel().also {
        it.submit(CheckoutRequest(customer, cartVm.items, OrderType.DELIVERY, "VN", PaymentMethod.CARD, "visa-4242", deliveryAddress = Address("12 Nguyen Hue")))
    }.uiState.value as CheckoutUiState.Success
    val id2 = second.confirmation.orderId
    val vm2 = app.newOrderTrackingViewModel(id2)
    app.lifecycle.confirm(id2); app.lifecycle.startPreparing(id2)
    vm2.cancel()
    println("cancel while PREPARING rejected: ${vm2.uiState.value.error}")
    app.lifecycle.startDelivery(id2); app.lifecycle.markDelivered(id2)
    show("delivered      ", vm2)
    vm2.requestRefund()
    vm2.rate(5)
    show("after refund   ", vm2)
    vm2.onCleared()

    println("\n=== 5. US pickup order and invoices (region decides the notation) ===")
    val usEditor = app.newCartEditor("c-2").also { it.add(app.menu.findItem("cheeseburger")!!, 2) }
    val us = app.newCheckoutViewModel().also {
        it.submit(CheckoutRequest(customer, usEditor.cart.items, OrderType.PICKUP, "US", PaymentMethod.CARD, "visa-1111"))
    }.uiState.value as CheckoutUiState.Success
    printInvoice(app, id2, InvoiceFormat.CSV)
    printInvoice(app, us.confirmation.orderId, InvoiceFormat.HTML)
    println("US total ${us.confirmation.price.total}; tax ${us.confirmation.price.tax}")

    println("\n=== 6. Promotion, messages sent and analytics ===")
    app.promoAnnouncer.announce(PromoCode("SAVE10", 10), listOf(customer))
    println("push=${app.sentMessages.on(ChannelType.PUSH).size} sms=${app.sentMessages.on(ChannelType.SMS).size} email=${app.sentMessages.on(ChannelType.EMAIL).size}")
    println("urgent messages: ${app.sentMessages.all().count { it.urgent }}; CONFIRMED entered ${app.statusCounter.count(OrderStatus.CONFIRMED)} times")
    println("menu items: ${InMemoryMenuRepository.SAMPLE_MENU.size}")
    trackingVm.onCleared()
}

private fun printInvoice(app: AppContainer, orderId: OrderId, format: InvoiceFormat) {
    val vm = app.newInvoiceViewModel(orderId)
    vm.export(format)
    when (val state = vm.uiState.value) {
        is InvoiceUiState.Ready -> println("--- invoice $orderId (${state.format}) ---\n${state.content}")
        is InvoiceUiState.Failed -> println("invoice $orderId failed: ${state.message}")
        InvoiceUiState.Idle -> Unit
    }
}
