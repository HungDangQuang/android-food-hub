package com.nemis.foodhub.domain.checkout

import com.nemis.foodhub.domain.ValidationException
import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.order.Order
import java.time.Duration

class DeliveryCheckout(services: CheckoutServices) : CheckoutProcess(services) {

    override fun validateTypeRules(ctx: CheckoutContext) {
        if (ctx.request.deliveryAddress?.line.isNullOrBlank()) {
            throw ValidationException("Delivery orders need a delivery address")
        }
    }

    override fun deliveryFee(ctx: CheckoutContext): Money = ctx.region.standardDeliveryFee

    override fun placedMessage(order: Order) = "Order ${order.id} placed. It will be delivered to your address."
}

class PickupCheckout(services: CheckoutServices) : CheckoutProcess(services) {

    override fun validateTypeRules(ctx: CheckoutContext) {
        if (ctx.request.scheduledAt != null) {
            throw ValidationException("Pickup orders cannot be scheduled; use a scheduled order instead")
        }
    }

    override fun placedMessage(order: Order) = "Order ${order.id} placed. Pick it up at the restaurant when it is ready."
}

class ScheduledCheckout(services: CheckoutServices) : CheckoutProcess(services) {

    override fun validateTypeRules(ctx: CheckoutContext) {
        if (ctx.request.deliveryAddress?.line.isNullOrBlank()) {
            throw ValidationException("Scheduled orders need a delivery address")
        }
        val at = ctx.request.scheduledAt ?: throw ValidationException("Scheduled orders need a delivery time")
        val now = services.clock.instant()
        if (at.isBefore(now.plus(MIN_LEAD_TIME))) {
            throw ValidationException("Scheduled time must be at least ${MIN_LEAD_TIME.toMinutes()} minutes ahead")
        }
        if (at.isAfter(now.plus(MAX_LEAD_TIME))) {
            throw ValidationException("Scheduled time must be within ${MAX_LEAD_TIME.toDays()} days")
        }
    }

    override fun deliveryFee(ctx: CheckoutContext): Money = ctx.region.standardDeliveryFee

    override fun placedMessage(order: Order) =
        "Order ${order.id} scheduled for ${order.scheduledAt}. We will deliver it then."

    companion object {
        val MIN_LEAD_TIME: Duration = Duration.ofMinutes(30)
        val MAX_LEAD_TIME: Duration = Duration.ofDays(7)
    }
}
