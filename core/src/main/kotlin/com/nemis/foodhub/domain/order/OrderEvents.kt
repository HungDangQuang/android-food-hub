package com.nemis.foodhub.domain.order

import com.nemis.foodhub.domain.model.Customer
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderStatus
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class OrderStatusChanged(
    val orderId: OrderId,
    val customer: Customer,
    val previous: OrderStatus,
    val current: OrderStatus,
    val capabilities: OrderCapabilities,
    val occurredAt: Instant
)

/** Observer (C2). */
fun interface OrderStatusListener {
    fun onOrderStatusChanged(event: OrderStatusChanged)
}

/** Handle returned by subscribe; whoever subscribes is responsible for cancelling (e.g. ViewModel.onCleared). */
interface Subscription {
    val isActive: Boolean
    fun cancel()
}

/** Subject. The publisher knows only the [OrderStatusListener] abstraction, never who is listening. */
interface OrderEventPublisher {
    fun publish(event: OrderStatusChanged)

    /** [orderId] == null means all orders. */
    fun subscribe(listener: OrderStatusListener, orderId: OrderId? = null): Subscription
}

/**
 * Thread-safe subject:
 *  - copy-on-write registrations, so listeners may subscribe/cancel during a dispatch;
 *  - a failing listener is isolated and cannot stop the others;
 *  - listeners run on the publishing thread, so UI listeners must hand over to their own thread
 *    (a ViewModel does this by writing to a thread-safe StateFlow).
 */
class SynchronousOrderEventPublisher(
    private val onListenerFailure: (OrderStatusListener, Exception) -> Unit = { _, _ -> }
) : OrderEventPublisher {

    private class Registration(val listener: OrderStatusListener, val orderId: OrderId?) {
        val active = AtomicBoolean(true)
    }

    private val registrations = CopyOnWriteArrayList<Registration>()

    override fun subscribe(listener: OrderStatusListener, orderId: OrderId?): Subscription {
        val registration = Registration(listener, orderId)
        registrations.add(registration)
        return object : Subscription {
            override val isActive: Boolean get() = registration.active.get()
            override fun cancel() {
                registration.active.set(false)
                registrations.remove(registration)
            }
        }
    }

    override fun publish(event: OrderStatusChanged) {
        for (registration in registrations) {
            if (!registration.active.get()) continue
            if (registration.orderId != null && registration.orderId != event.orderId) continue
            try {
                registration.listener.onOrderStatusChanged(event)
            } catch (e: Exception) {
                onListenerFailure(registration.listener, e)
            }
        }
    }
}

/** Analytics observer: counts how many times each status was entered. */
class OrderStatusCounter : OrderStatusListener {
    private val counts = ConcurrentHashMap<OrderStatus, AtomicInteger>()

    override fun onOrderStatusChanged(event: OrderStatusChanged) {
        counts.computeIfAbsent(event.current) { AtomicInteger() }.incrementAndGet()
    }

    fun count(status: OrderStatus): Int = counts[status]?.get() ?: 0
}
