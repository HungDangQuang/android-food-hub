package com.nemis.foodhub.domain.notification

import com.nemis.foodhub.domain.model.ChannelType
import com.nemis.foodhub.domain.model.Customer
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.order.OrderStatusChanged
import com.nemis.foodhub.domain.order.OrderStatusListener
import com.nemis.foodhub.domain.pricing.PromoCode

enum class Priority { NORMAL, HIGH }

data class Message(val title: String, val body: String, val priority: Priority)

/** Bridge (C5), implementor side: HOW a message is delivered. Channel-specific formatting lives here. */
interface NotificationChannel {
    val type: ChannelType
    fun deliver(recipient: Customer, message: Message)
}

/**
 * Bridge (C5), abstraction side: WHAT is said. The two hierarchies vary independently:
 * 3 kinds x 3 channels = 6 classes instead of 9, and a 4th channel adds exactly one class.
 */
abstract class Notification(private val channel: NotificationChannel) {
    protected abstract fun buildMessage(): Message

    fun send(recipient: Customer) = channel.deliver(recipient, buildMessage())
}

class OrderUpdateNotification(channel: NotificationChannel, private val orderId: OrderId, private val text: String) :
    Notification(channel) {
    override fun buildMessage() = Message("Order $orderId", text, Priority.NORMAL)
}

class PromoNotification(channel: NotificationChannel, private val headline: String) : Notification(channel) {
    override fun buildMessage() = Message("FoodHub offer", headline, Priority.NORMAL)
}

class UrgentNotification(channel: NotificationChannel, private val orderId: OrderId, private val text: String) :
    Notification(channel) {
    override fun buildMessage() = Message("Urgent: order $orderId", text, Priority.HIGH)
}

/** What the rest of the domain uses; it does not know channels or notification classes. */
interface CustomerNotifier {
    fun notifyOrderUpdate(customer: Customer, orderId: OrderId, text: String)
    fun notifyUrgent(customer: Customer, orderId: OrderId, text: String)
    fun notifyPromo(customer: Customer, headline: String)
}

/**
 * Sends through every channel the customer opted into. A failing channel is reported through [onChannelFailure]
 * and does not prevent the other channels from being tried.
 */
class MultiChannelNotifier(
    channels: List<NotificationChannel>,
    private val onChannelFailure: (ChannelType, Exception) -> Unit = { _, _ -> }
) : CustomerNotifier {

    private val channels: Map<ChannelType, NotificationChannel> = channels.associateBy { it.type }

    override fun notifyOrderUpdate(customer: Customer, orderId: OrderId, text: String) =
        broadcast(customer) { OrderUpdateNotification(it, orderId, text) }

    override fun notifyUrgent(customer: Customer, orderId: OrderId, text: String) =
        broadcast(customer) { UrgentNotification(it, orderId, text) }

    override fun notifyPromo(customer: Customer, headline: String) =
        broadcast(customer) { PromoNotification(it, headline) }

    private fun broadcast(customer: Customer, create: (NotificationChannel) -> Notification) {
        for (type in customer.channels) {
            val channel = channels[type] ?: continue
            try {
                create(channel).send(customer)
            } catch (e: Exception) {
                onChannelFailure(type, e)
            }
        }
    }
}

/** Observer that turns status changes into customer notifications (urgent when [com.nemis.foodhub.domain.model.OrderStatus.urgent]). */
class NotificationOrderListener(private val notifier: CustomerNotifier) : OrderStatusListener {
    override fun onOrderStatusChanged(event: OrderStatusChanged) {
        val text = event.current.customerMessage
        if (event.current.urgent) {
            notifier.notifyUrgent(event.customer, event.orderId, text)
        } else {
            notifier.notifyOrderUpdate(event.customer, event.orderId, text)
        }
    }
}

/** Stand-in for the Admin tooling: announces a promotion to customers through the channels they opted into. */
class PromoAnnouncer(private val notifier: CustomerNotifier) {
    fun announce(promo: PromoCode, customers: List<Customer>) {
        val headline = "Use ${promo.code} for ${promo.percentOff}% off your order"
        customers.forEach { notifier.notifyPromo(it, headline) }
    }
}
