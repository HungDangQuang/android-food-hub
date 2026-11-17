package com.nemis.foodhub.domain.pricing

import com.nemis.foodhub.domain.ValidationException
import com.nemis.foodhub.domain.model.CartItem
import com.nemis.foodhub.domain.model.Money

data class PromoCode(val code: String, val percentOff: Int) {
    init {
        require(percentOff in 1..100) { "percentOff must be in 1..100" }
    }
}

interface PromoCatalog {
    fun find(code: String): PromoCode?
}

/** Part of the regional product family created by RegionFactory. */
interface TaxCalculator {
    fun taxFor(taxableAmount: Money): Money
}

data class PriceBreakdown(
    val subtotal: Money,
    val discount: Money,
    val deliveryFee: Money,
    val tax: Money,
    val total: Money
)

/** Pure calculation. Tax rules come from the region (TaxCalculator), never from this class. */
class PricingService {

    /** Tax is charged on (subtotal - discount); the delivery fee is not taxed. */
    fun price(items: List<CartItem>, promo: PromoCode?, deliveryFee: Money, tax: TaxCalculator): PriceBreakdown {
        if (items.isEmpty()) throw ValidationException("Cannot price an empty cart")
        val currency = deliveryFee.currency
        val subtotal = items.fold(Money.zero(currency)) { acc, line -> acc + line.lineTotal }
        val discount = promo?.let { subtotal.percent(it.percentOff) } ?: Money.zero(currency)
        val taxable = subtotal - discount
        val taxAmount = tax.taxFor(taxable)
        return PriceBreakdown(subtotal, discount, deliveryFee, taxAmount, taxable + deliveryFee + taxAmount)
    }
}
