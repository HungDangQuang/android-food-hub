package com.nemis.foodhub.domain

import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderStatus
import java.time.Duration

open class DomainException(message: String) : RuntimeException(message)

open class OrderException(message: String) : DomainException(message)

/** Thrown by a state that does not allow the requested operation: invalid transitions are never silent. */
class IllegalOrderOperationException(
    val status: OrderStatus,
    val operation: String,
    reason: String? = null
) : OrderException("Cannot '$operation' when order is $status" + (reason?.let { " ($it)" } ?: ""))

class RefundWindowExpiredException(window: Duration) :
    OrderException("Refund window of ${window.toHours()}h after delivery has expired")

class OrderNotFoundException(id: OrderId) : OrderException("Order $id not found")

class ValidationException(message: String) : DomainException(message)

class PaymentFailedException(message: String) : DomainException(message)

class OutOfStockException(itemId: String) : DomainException("Item $itemId is out of stock")

class UnsupportedRegionException(code: String) : DomainException("Region '$code' is not supported")
