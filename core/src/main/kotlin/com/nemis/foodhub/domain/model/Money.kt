package com.nemis.foodhub.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

/** A value type rather than an enum, so a new region can bring its own currency without touching this file (OCP). */
data class Currency(val code: String, val fractionDigits: Int) {
    override fun toString() = code

    companion object {
        val VND = Currency("VND", 0)
        val USD = Currency("USD", 2)
    }
}

/**
 * Immutable value object. The amount is always normalised to the currency's fraction digits,
 * so equals/hashCode are consistent. Mixing currencies is a programming error.
 */
class Money(amount: BigDecimal, val currency: Currency) : Comparable<Money> {

    val amount: BigDecimal = amount.setScale(currency.fractionDigits, RoundingMode.HALF_UP)

    val isZero: Boolean get() = amount.signum() == 0
    val isPositive: Boolean get() = amount.signum() > 0

    operator fun plus(other: Money) = Money(amount.add(sameCurrency(other).amount), currency)
    operator fun minus(other: Money) = Money(amount.subtract(sameCurrency(other).amount), currency)
    operator fun times(quantity: Int) = Money(amount.multiply(BigDecimal(quantity)), currency)

    fun percent(rate: BigDecimal): Money =
        Money(amount.multiply(rate).divide(HUNDRED, 10, RoundingMode.HALF_UP), currency)

    fun percent(rate: Int): Money = percent(BigDecimal(rate))

    override fun compareTo(other: Money): Int = amount.compareTo(sameCurrency(other).amount)

    override fun equals(other: Any?): Boolean =
        other is Money && other.currency == currency && other.amount == amount

    override fun hashCode(): Int = 31 * amount.hashCode() + currency.hashCode()

    override fun toString(): String = "${amount.toPlainString()} ${currency.code}"

    private fun sameCurrency(other: Money): Money {
        require(other.currency == currency) { "Currency mismatch: ${other.currency} vs $currency" }
        return other
    }

    companion object {
        private val HUNDRED = BigDecimal(100)

        fun zero(currency: Currency) = Money(BigDecimal.ZERO, currency)
        fun of(amount: String, currency: Currency) = Money(BigDecimal(amount), currency)
        fun of(amount: Long, currency: Currency) = Money(BigDecimal.valueOf(amount), currency)
    }
}
