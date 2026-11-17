package com.nemis.foodhub

import com.nemis.foodhub.data.channel.EmailChannel
import com.nemis.foodhub.data.channel.PushChannel
import com.nemis.foodhub.data.channel.SentMessageLog
import com.nemis.foodhub.data.channel.SmsChannel
import com.nemis.foodhub.domain.model.ChannelType
import com.nemis.foodhub.domain.model.Customer
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderStatus
import com.nemis.foodhub.domain.notification.CustomerNotifier
import com.nemis.foodhub.domain.notification.Message
import com.nemis.foodhub.domain.notification.MultiChannelNotifier
import com.nemis.foodhub.domain.notification.Notification
import com.nemis.foodhub.domain.notification.NotificationChannel
import com.nemis.foodhub.domain.notification.NotificationOrderListener
import com.nemis.foodhub.domain.notification.OrderUpdateNotification
import com.nemis.foodhub.domain.notification.Priority
import com.nemis.foodhub.domain.notification.PromoNotification
import com.nemis.foodhub.domain.notification.UrgentNotification
import com.nemis.foodhub.domain.order.OrderCapabilities
import com.nemis.foodhub.domain.order.OrderStatusChanged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** C5: Bridge pattern. */
class NotificationBridgeTest {

    private class RecordingChannel(override val type: ChannelType) : NotificationChannel {
        val received = mutableListOf<Message>()
        override fun deliver(recipient: Customer, message: Message) {
            received.add(message)
        }
    }

    private class BrokenChannel(override val type: ChannelType) : NotificationChannel {
        override fun deliver(recipient: Customer, message: Message) = throw IllegalStateException("gateway down")
    }

    private val customer = Fixtures.customer
    private val orderId = OrderId("ORD-1")

    @Test
    fun everyKindOfNotificationCanTravelOverEveryChannel() {
        val channels = listOf(ChannelType.PUSH, ChannelType.SMS, ChannelType.EMAIL).map { RecordingChannel(it) }
        for (channel in channels) {
            val kinds: List<Notification> = listOf(
                OrderUpdateNotification(channel, orderId, "on its way"),
                PromoNotification(channel, "20% off"),
                UrgentNotification(channel, orderId, "cancelled")
            )
            kinds.forEach { it.send(customer) }

            assertEquals(3, channel.received.size)
            assertEquals(listOf(Priority.NORMAL, Priority.NORMAL, Priority.HIGH), channel.received.map { it.priority })
            assertEquals("on its way", channel.received[0].body)
            assertTrue(channel.received[2].title.contains("Urgent"))
        }
    }

    @Test
    fun channelsFormatTheSameMessageTheirOwnWay() {
        val log = SentMessageLog()
        val long = Message("Order ORD-1", "x".repeat(400), Priority.NORMAL)

        PushChannel(log).deliver(customer, long)
        SmsChannel(log).deliver(customer, long)
        EmailChannel(log).deliver(customer, long)

        val push = log.on(ChannelType.PUSH).single()
        val sms = log.on(ChannelType.SMS).single()
        val email = log.on(ChannelType.EMAIL).single()
        assertEquals(customer.id, push.to)
        assertEquals(400, push.body.length)
        assertEquals(customer.phone, sms.to)
        assertEquals(SmsChannel.MAX_LENGTH, sms.body.length)
        assertEquals(customer.email, email.to)
        assertEquals("[FoodHub] Order ORD-1", email.subject())
    }

    private fun com.nemis.foodhub.domain.notification.SentNotification.subject() = title

    @Test
    fun onlyTheChannelsTheCustomerOptedIntoAreUsed() {
        val push = RecordingChannel(ChannelType.PUSH)
        val sms = RecordingChannel(ChannelType.SMS)
        val notifier = MultiChannelNotifier(listOf(push, sms))

        notifier.notifyOrderUpdate(customer.copy(channels = setOf(ChannelType.SMS)), orderId, "hello")

        assertTrue(push.received.isEmpty())
        assertEquals(1, sms.received.size)
    }

    @Test
    fun aBrokenChannelIsReportedAndDoesNotBlockTheOthers() {
        val failures = mutableListOf<ChannelType>()
        val healthy = RecordingChannel(ChannelType.EMAIL)
        val notifier = MultiChannelNotifier(listOf(BrokenChannel(ChannelType.PUSH), healthy)) { type, _ -> failures.add(type) }

        notifier.notifyUrgent(customer, orderId, "cancelled")

        assertEquals(listOf(ChannelType.PUSH), failures)
        assertEquals(1, healthy.received.size)
    }

    @Test
    fun aNewChannelIsOneNewClassAndNothingElseChanges() {
        val chat = ChannelType("CHAT")
        val received = mutableListOf<Message>()
        val chatChannel = object : NotificationChannel {
            override val type = chat
            override fun deliver(recipient: Customer, message: Message) { received.add(message) }
        }
        val notifier = MultiChannelNotifier(listOf(PushChannel(SentMessageLog()), chatChannel))

        notifier.notifyPromo(customer.copy(channels = setOf(chat)), "free fries")

        assertEquals("free fries", received.single().body)
    }

    // ---- observer -> bridge: status changes become notifications ----

    private class RecordingNotifier : CustomerNotifier {
        val calls = mutableListOf<String>()
        override fun notifyOrderUpdate(customer: Customer, orderId: OrderId, text: String) { calls.add("update:$text") }
        override fun notifyUrgent(customer: Customer, orderId: OrderId, text: String) { calls.add("urgent:$text") }
        override fun notifyPromo(customer: Customer, headline: String) { calls.add("promo:$headline") }
    }

    private fun changed(to: OrderStatus) = OrderStatusChanged(
        orderId, customer, OrderStatus.CREATED, to, OrderCapabilities.NONE, Instant.EPOCH, version = 1
    )

    @Test
    fun statusChangesBecomeUpdatesAndCancellationBecomesUrgent() {
        val notifier = RecordingNotifier()
        val listener = NotificationOrderListener(notifier)

        listener.onOrderStatusChanged(changed(OrderStatus.CONFIRMED))
        listener.onOrderStatusChanged(changed(OrderStatus.CANCELLED))

        assertEquals(
            listOf("update:${OrderStatus.CONFIRMED.customerMessage}", "urgent:${OrderStatus.CANCELLED.customerMessage}"),
            notifier.calls
        )
    }

    @Test
    fun cancellingARealOrderSendsUrgentMessagesOnEveryChannel() {
        val app = Fixtures.newApp()
        val order = orderIn(app, OrderStatus.CREATED)
        val before = app.sentMessages.all().size

        app.facade.cancelOrder(order.id)

        val sent = app.sentMessages.all().drop(before)
        assertEquals(3, sent.size)
        assertTrue(sent.all { it.urgent })
    }
}
