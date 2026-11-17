package com.nemis.foodhub.data.channel

import com.nemis.foodhub.domain.model.ChannelType
import com.nemis.foodhub.domain.model.Customer
import com.nemis.foodhub.domain.notification.Message
import com.nemis.foodhub.domain.notification.NotificationChannel
import com.nemis.foodhub.domain.notification.Priority
import java.util.concurrent.CopyOnWriteArrayList

data class SentMessage(val channel: ChannelType, val to: String, val title: String, val body: String, val urgent: Boolean)

/** Stand-in for the real push/SMS/email gateways: records what would have been sent. */
class SentMessageLog {
    private val messages = CopyOnWriteArrayList<SentMessage>()
    fun record(message: SentMessage) {
        messages.add(message)
    }

    fun all(): List<SentMessage> = messages.toList()
    fun on(channel: ChannelType): List<SentMessage> = messages.filter { it.channel == channel }
}

class PushChannel(private val log: SentMessageLog) : NotificationChannel {
    override val type = ChannelType.PUSH
    override fun deliver(recipient: Customer, message: Message) = log.record(
        SentMessage(type, recipient.id, message.title, message.body, message.priority == Priority.HIGH)
    )
}

/** SMS has no title and a 160 character limit. */
class SmsChannel(private val log: SentMessageLog) : NotificationChannel {
    override val type = ChannelType.SMS
    override fun deliver(recipient: Customer, message: Message) {
        val text = "${message.title}: ${message.body}".take(MAX_LENGTH)
        log.record(SentMessage(type, recipient.phone, "", text, message.priority == Priority.HIGH))
    }

    companion object {
        const val MAX_LENGTH = 160
    }
}

class EmailChannel(private val log: SentMessageLog) : NotificationChannel {
    override val type = ChannelType.EMAIL
    override fun deliver(recipient: Customer, message: Message) = log.record(
        SentMessage(type, recipient.email, "[FoodHub] ${message.title}", message.body, message.priority == Priority.HIGH)
    )
}
