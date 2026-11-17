package com.nemis.foodhub.domain.model

import java.time.Instant

data class MenuItem(val id: String, val restaurantId: String, val name: String, val price: Money)

data class CartItem(val item: MenuItem, val quantity: Int) {
    init {
        require(quantity > 0) { "quantity must be positive" }
    }

    val lineTotal: Money get() = item.price * quantity
}

/** A value type rather than an enum, so a new channel (e.g. chat app) can be added without editing this file (OCP). */
@JvmInline
value class ChannelType(val id: String) {
    override fun toString() = id

    companion object {
        val PUSH = ChannelType("PUSH")
        val SMS = ChannelType("SMS")
        val EMAIL = ChannelType("EMAIL")
    }
}

data class Address(val line: String)

data class Customer(
    val id: String,
    val name: String,
    val phone: String,
    val email: String,
    val channels: Set<ChannelType>
)

@JvmInline
value class OrderId(val value: String) {
    override fun toString() = value
}

/** [requiresCourier] drives which part of the lifecycle an order goes through (see PreparingState). */
enum class OrderType(val requiresCourier: Boolean) {
    DELIVERY(true),
    PICKUP(false),
    SCHEDULED(true)
}

/**
 * Descriptive tag of an order's state: identity, customer-facing text and whether the customer must be alerted
 * urgently.
 * No behaviour is selected by switching on this enum; behaviour lives in OrderState subclasses.
 */
enum class OrderStatus(val customerMessage: String, val urgent: Boolean = false) {
    CREATED("Order placed, waiting for the restaurant to confirm"),
    CONFIRMED("Restaurant confirmed your order"),
    PREPARING("Restaurant is preparing your order"),
    DELIVERING("Courier is on the way"),
    DELIVERED("Order delivered. Enjoy your meal!"),
    CANCELLED("Order cancelled", urgent = true)
}

data class StatusTransition(val from: OrderStatus, val to: OrderStatus, val at: Instant)
