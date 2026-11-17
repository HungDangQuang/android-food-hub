package com.nemis.foodhub.domain.region.us

import com.nemis.foodhub.domain.invoice.BaseInvoiceFormatter
import com.nemis.foodhub.domain.invoice.InvoiceFormatter
import com.nemis.foodhub.domain.invoice.InvoiceLabels
import com.nemis.foodhub.domain.model.Currency
import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.payment.PaymentMethod
import com.nemis.foodhub.domain.payment.PaymentProcessor
import com.nemis.foodhub.domain.payment.SimulatedPaymentProcessor
import com.nemis.foodhub.domain.payment.SimulatedRefundableProcessor
import com.nemis.foodhub.domain.pricing.TaxCalculator
import com.nemis.foodhub.domain.region.Region
import com.nemis.foodhub.domain.region.RegionFactory
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class UsRegionFactory : RegionFactory(REGION) {

    override fun newPaymentProcessor(method: PaymentMethod): PaymentProcessor = when (method) {
        PaymentMethod.CARD -> SimulatedRefundableProcessor(method, Currency.USD, "Stripe")
        PaymentMethod.WALLET -> SimulatedRefundableProcessor(method, Currency.USD, "ApplePay")
        PaymentMethod.COD -> SimulatedPaymentProcessor(method, Currency.USD, "COD-US", collectsAtCheckout = false)
    }

    override fun createTaxCalculator(): TaxCalculator = UsSalesTaxCalculator()

    override fun createInvoiceFormatter(): InvoiceFormatter = UsInvoiceFormatter()

    companion object {
        const val CODE = "US"
        val REGION = Region(CODE, Currency.USD, Money.of("3.99", Currency.USD))
    }
}

/** Simulated rule: flat 7% sales tax on prepared food. */
class UsSalesTaxCalculator : TaxCalculator {
    override fun taxFor(taxableAmount: Money): Money = taxableAmount.percent(BigDecimal(7))
}

class UsInvoiceFormatter : BaseInvoiceFormatter() {
    override val labels = InvoiceLabels(
        title = "Invoice", order = "Order", customer = "Customer", date = "Date", payment = "Payment",
        item = "Item", quantity = "Qty", unitPrice = "Unit price", lineTotal = "Line total",
        subtotal = "Subtotal", discount = "Discount", deliveryFee = "Delivery fee",
        tax = "Sales tax", total = "Total"
    )

    override fun money(value: Money): String =
        "$" + DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US)).format(value.amount)
}
