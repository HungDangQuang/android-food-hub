package com.nemis.foodhub.app

import com.nemis.foodhub.domain.invoice.InvoiceFormat
import com.nemis.foodhub.domain.model.ChannelType
import com.nemis.foodhub.domain.model.Customer
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderStatus
import com.nemis.foodhub.domain.model.OrderType
import com.nemis.foodhub.domain.payment.PaymentMethod
import com.nemis.foodhub.presentation.CartViewModel
import com.nemis.foodhub.presentation.CheckoutForm
import com.nemis.foodhub.presentation.CheckoutUiState
import com.nemis.foodhub.presentation.CheckoutViewModel
import com.nemis.foodhub.presentation.InvoiceUiState
import com.nemis.foodhub.presentation.OrderTrackingViewModel
import java.nio.file.Files

/**
 * Demo "View": a console that only forwards input to ViewModels and prints their state.
 * Run with: ./gradlew :core:run
 */
fun main() {
    val draftDir = Files.createTempDirectory("foodhub-drafts").toFile()
    val app = AppContainer(draftDirectory = draftDir)
    val customer = Customer("c-1", "Hung", "+84900000001", "hung@example.com", setOf(ChannelType.PUSH, ChannelType.SMS, ChannelType.EMAIL))

    println("=== 0. Browse the menu ===")
    val menuVm = app.newMenuViewModel()
    menuVm.uiState.value.restaurants.forEach { restaurant ->
        menuVm.showRestaurant(restaurant.id)
        println("${restaurant.id} (${restaurant.regionCode}): ${menuVm.uiState.value.items.map { "${it.name} (${it.price})" }}")
    }

    println("\n=== 1. Cart: add, undo, crash recovery from disk ===")
    val editor = app.newCartEditor(customer.id)
    val cartVm = app.newCartViewModel(editor)
    cartVm.addItem("pho-bo", 2)
    cartVm.addItem("ca-phe-sua")
    println("cart: ${lines(cartVm)}")
    cartVm.undo()
    println("after undo: ${lines(cartVm)}")
    // A second container on the same directory stands for the app after the process was killed.
    val afterRestart = AppContainer(draftDirectory = draftDir).let { it.newCartViewModel(it.newCartEditor(customer.id)) }
    println("after restart: cart ${lines(afterRestart)}, draft offered=${afterRestart.uiState.value.canRestoreDraft}")
    afterRestart.restoreDraft()
    println("restored from disk: ${lines(afterRestart)}")
    afterRestart.onCleared()

    println("\n=== 2. Checkout (VN, delivery, MoMo wallet, promo SAVE10) ===")
    val checkoutVm = app.newCheckoutViewModel(editor, customer)
    val orderId = place(checkoutVm, CheckoutForm(OrderType.DELIVERY, PaymentMethod.WALLET, "momo-0901", "SAVE10", "12 Nguyen Hue, District 1"))
    println("cart after checkout: ${lines(cartVm)}")

    println("\n=== 3. Real-time tracking (Observer) and state-dependent actions (State) ===")
    val trackingVm = app.newOrderTrackingViewModel(orderId)
    val simulator = app.newSimulatorViewModel(orderId, listOf(customer))
    show("initial        ", trackingVm)
    trackingVm.setItemQuantity("pho-bo", 3)
    println("   (items edited while CREATED: ${trackingVm.uiState.value.lines.map { "${it.name} x${it.quantity}" }}, total ${trackingVm.uiState.value.total})")
    simulator.confirm(); show("restaurant ok  ", trackingVm)
    trackingVm.setItemQuantity("pho-bo", 1)
    println("   (edit after CONFIRMED rejected: ${trackingVm.uiState.value.error})")
    trackingVm.cancel()
    println("   (cancel after confirm: ${trackingVm.uiState.value.status}, ${trackingVm.uiState.value.notice})")

    println("\n=== 4. Second order: full lifecycle, then refund and rating ===")
    cartVm.addItem("pho-bo", 2)
    val id2 = place(checkoutVm, CheckoutForm(OrderType.DELIVERY, PaymentMethod.CARD, "visa-4242", deliveryAddress = "12 Nguyen Hue"))
    val vm2 = app.newOrderTrackingViewModel(id2)
    val sim2 = app.newSimulatorViewModel(id2, listOf(customer))
    sim2.confirm(); sim2.startPreparing()
    vm2.cancel()
    println("cancel while PREPARING rejected: ${vm2.uiState.value.error}")
    sim2.startDelivery(); sim2.markDelivered()
    show("delivered      ", vm2)
    vm2.requestRefund()
    println("   (${vm2.uiState.value.notice})")
    vm2.rate(5)
    show("after refund   ", vm2)
    vm2.onCleared()

    println("\n=== 5. US pickup order and invoices (region decides the notation) ===")
    val usEditor = app.newCartEditor("c-2").also { it.add(app.menu.findItem("cheeseburger")!!, 2) }
    val usId = place(app.newCheckoutViewModel(usEditor, customer), CheckoutForm(OrderType.PICKUP, PaymentMethod.CARD, "visa-1111"))
    printInvoice(app, id2, InvoiceFormat.CSV)
    printInvoice(app, usId, InvoiceFormat.HTML)

    println("\n=== 6. Promotion, messages sent and analytics ===")
    val log = app.newNotificationLogViewModel()
    simulator.announcePromo("SAVE10")
    println("push=${app.sentMessages.on(ChannelType.PUSH).size} sms=${app.sentMessages.on(ChannelType.SMS).size} email=${app.sentMessages.on(ChannelType.EMAIL).size}")
    println("latest: ${log.uiState.value.messages.first().let { "${it.channel} -> ${it.to}: ${it.body}" }}")
    println("urgent messages: ${app.sentMessages.all().count { it.urgent }}; CONFIRMED entered ${app.statusCounter.count(OrderStatus.CONFIRMED)} times")
    log.onCleared()
    trackingVm.onCleared()
    cartVm.onCleared()
}

private fun lines(vm: CartViewModel) = vm.uiState.value.lines.map { "${it.name} x${it.quantity}" }

private fun place(vm: CheckoutViewModel, form: CheckoutForm): OrderId {
    vm.submit(form)
    val success = vm.uiState.value as CheckoutUiState.Success
    vm.acknowledge()
    with(success.confirmation) { println("placed $orderId: total ${price.total} (tax ${price.tax}), status $status") }
    return success.confirmation.orderId
}

private fun show(label: String, vm: OrderTrackingViewModel) = vm.uiState.value.let { s ->
    println("$label -> ${s.status} | edit=${s.canEditItems} cancel=${s.canCancel}(fee ${s.cancellationFeePercent}%) courier=${s.canTrackCourier} refund=${s.canRequestRefund} rate=${s.canRate}")
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
