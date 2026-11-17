package com.nemis.foodhub

import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderStatus
import com.nemis.foodhub.domain.order.OrderCapabilities
import com.nemis.foodhub.domain.order.OrderStatusChanged
import com.nemis.foodhub.domain.order.OrderStatusCounter
import com.nemis.foodhub.domain.order.OrderStatusListener
import com.nemis.foodhub.domain.order.SynchronousOrderEventPublisher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** C2: Observer pattern. */
class OrderObserverTest {

    private fun event(id: String = "o-1", status: OrderStatus = OrderStatus.CONFIRMED) = OrderStatusChanged(
        OrderId(id), Fixtures.customer, OrderStatus.CREATED, status, OrderCapabilities.NONE, Instant.EPOCH
    )

    @Test
    fun everySubscriberIsNotifiedAndThePublisherKnowsNoneOfThem() {
        val publisher = SynchronousOrderEventPublisher()
        val a = EventLog().also { publisher.subscribe(it) }
        val b = EventLog().also { publisher.subscribe(it) }
        publisher.publish(event())
        assertEquals(1, a.events.size)
        assertEquals(1, b.events.size)
    }

    @Test
    fun subscriberCanFilterOnOneOrder() {
        val publisher = SynchronousOrderEventPublisher()
        val only1 = EventLog().also { publisher.subscribe(it, OrderId("o-1")) }
        val all = EventLog().also { publisher.subscribe(it) }
        publisher.publish(event("o-1")); publisher.publish(event("o-2"))
        assertEquals(1, only1.events.size)
        assertEquals(2, all.events.size)
    }

    @Test
    fun cancelledSubscriptionNoLongerReceivesEvents() {
        val publisher = SynchronousOrderEventPublisher()
        val log = EventLog()
        val subscription = publisher.subscribe(log)
        publisher.publish(event())
        assertTrue(subscription.isActive)

        subscription.cancel()
        publisher.publish(event())

        assertFalse(subscription.isActive)
        assertEquals(1, log.events.size)
        subscription.cancel() // cancelling twice is harmless
    }

    @Test
    fun aFailingListenerDoesNotStopTheOthers() {
        val failures = mutableListOf<Exception>()
        val publisher = SynchronousOrderEventPublisher { _, e -> failures.add(e) }
        publisher.subscribe(OrderStatusListener { throw IllegalStateException("boom") })
        val healthy = EventLog().also { publisher.subscribe(it) }

        publisher.publish(event())

        assertEquals(1, healthy.events.size)
        assertEquals("boom", failures.single().message)
    }

    @Test
    fun aListenerMayCancelItselfWhileBeingNotified() {
        val publisher = SynchronousOrderEventPublisher()
        val calls = AtomicInteger()
        lateinit var self: com.nemis.foodhub.domain.order.Subscription
        self = publisher.subscribe(OrderStatusListener { calls.incrementAndGet(); self.cancel() })
        val other = EventLog().also { publisher.subscribe(it) }

        publisher.publish(event()); publisher.publish(event())

        assertEquals(1, calls.get())
        assertEquals(2, other.events.size)
    }

    @Test(timeout = 20_000)
    fun publishingAndSubscribingFromManyThreadsLosesNothing() {
        val publisher = SynchronousOrderEventPublisher()
        val permanent = AtomicInteger()
        publisher.subscribe(OrderStatusListener { permanent.incrementAndGet() })
        val threads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.execute {
                repeat(perThread) {
                    val temporary = publisher.subscribe(OrderStatusListener { })
                    publisher.publish(event())
                    temporary.cancel()
                }
                done.countDown()
            }
        }
        assertTrue(done.await(15, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(threads * perThread, permanent.get())
    }

    @Test(timeout = 10_000)
    fun orderPublishesAfterReleasingItsLockSoListenersCanReadItFromAnotherThread() {
        val app = Fixtures.newApp()
        val order = orderIn(app, OrderStatus.CREATED)
        val readFromOtherThread = CountDownLatch(1)
        app.events.subscribe(OrderStatusListener {
            val reader = Thread { order.status; readFromOtherThread.countDown() }
            reader.start()
            reader.join(3_000) // would block forever if the order still held its lock while publishing
        })

        order.confirm()

        assertTrue(readFromOtherThread.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun statusCounterActsAsAnAnalyticsObserver() {
        val app = Fixtures.newApp()
        val order = orderIn(app, OrderStatus.CREATED)
        order.confirm(); order.startPreparing()
        assertEquals(1, app.statusCounter.count(OrderStatus.CONFIRMED))
        assertEquals(1, app.statusCounter.count(OrderStatus.PREPARING))
        assertEquals(0, app.statusCounter.count(OrderStatus.DELIVERED))
        assertEquals(0, OrderStatusCounter().count(OrderStatus.CONFIRMED))
    }

    @Test
    fun uiNotificationAndAnalyticsAllReactToOneTransition() {
        val app = Fixtures.newApp()
        val order = orderIn(app, OrderStatus.CREATED)
        val sentBefore = app.sentMessages.all().size
        val ui = EventLog().also { app.events.subscribe(it, order.id) }

        order.confirm()

        assertEquals(1, ui.events.size)
        assertEquals(1, app.statusCounter.count(OrderStatus.CONFIRMED))
        assertEquals(3, app.sentMessages.all().size - sentBefore) // push + sms + email
    }
}
