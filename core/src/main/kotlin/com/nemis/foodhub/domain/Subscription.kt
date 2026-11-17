package com.nemis.foodhub.domain

/**
 * Handle returned by every subscribe/observe in the domain (order events, cart changes, notification outbox).
 * Whoever subscribes is responsible for cancelling it (e.g. ViewModel.onCleared).
 */
interface Subscription {
    val isActive: Boolean
    fun cancel()
}
