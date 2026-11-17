package com.nemis.foodhub.data.channel

import com.nemis.foodhub.domain.Subscription
import com.nemis.foodhub.domain.model.ChannelType
import com.nemis.foodhub.domain.model.Customer
import com.nemis.foodhub.domain.notification.Message
import com.nemis.foodhub.domain.notification.NotificationChannel
import com.nemis.foodhub.domain.notification.NotificationOutbox
import com.nemis.foodhub.domain.notification.Priority
import com.nemis.foodhub.domain.notification.SentNotification
import java.util.concurrent.CopyOnWriteArrayList

/** Stand-in for the real push/SMS/email gateways: records what would have been sent and tells observers. */
class SentMessageLog : NotificationOutbox {
    private val messages = CopyOnWriteArrayList<SentNotification>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun record(message: SentNotification) {
        messages.add(message)
        listeners.forEach { it() }
    }

    fun all(): List<SentNotification> = messages.toList()
    fun on(channel: ChannelType): List<SentNotification> = messages.filter { it.channel == channel }

    override fun recent(limit: Int): List<SentNotification> = messages.toList().takeLast(limit).reversed()

    override fun observe(listener: () -> Unit): Subscription {
        val registration: () -> Unit = { listener() } // a fresh instance, so the same lambda can be registered twice
        listeners.add(registration)
        return object : Subscription {
            override val isActive: Boolean get() = registration in listeners
            override fun cancel() {
                listeners.remove(registration)
            }
        }
    }
}

class PushChannel(private val log: SentMessageLog) : NotificationChannel {
    override val type = ChannelType.PUSH
    override fun deliver(recipient: Customer, message: Message) = log.record(
        SentNotification(type, recipient.id, message.title, message.body, message.priority == Priority.HIGH)
    )
}

/** SMS has no title and a 160 character limit. */
class SmsChannel(private val log: SentMessageLog) : NotificationChannel {
    override val type = ChannelType.SMS
    override fun deliver(recipient: Customer, message: Message) {
        val text = "${message.title}: ${message.body}".take(MAX_LENGTH)
        log.record(SentNotification(type, recipient.phone, "", text, message.priority == Priority.HIGH))
    }

    companion object {
        const val MAX_LENGTH = 160
    }
}

class EmailChannel(private val log: SentMessageLog) : NotificationChannel {
    override val type = ChannelType.EMAIL
    override fun deliver(recipient: Customer, message: Message) = log.record(
        SentNotification(type, recipient.email, "[FoodHub] ${message.title}", message.body, message.priority == Priority.HIGH)
    )
}
