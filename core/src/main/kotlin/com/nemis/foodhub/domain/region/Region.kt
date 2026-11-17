package com.nemis.foodhub.domain.region

import com.nemis.foodhub.domain.UnsupportedRegionException
import com.nemis.foodhub.domain.invoice.InvoiceFormatter
import com.nemis.foodhub.domain.model.Currency
import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.payment.PaymentMethod
import com.nemis.foodhub.domain.payment.PaymentProcessor
import com.nemis.foodhub.domain.pricing.TaxCalculator

data class Region(val code: String, val currency: Currency, val standardDeliveryFee: Money)

/**
 * Abstract Factory (C3): one concrete factory per region creates a consistent family
 * {PaymentProcessor, TaxCalculator, InvoiceFormatter}, so a VN tax calculator can never be combined
 * with a US invoice formatter.
 *
 * [createPaymentProcessor] is also a Factory Method: it is the fixed entry point (creator) which delegates the
 * choice of concrete processor to the subclass hook [newPaymentProcessor], then enforces the shared contract.
 */
abstract class RegionFactory(val region: Region) {

    fun createPaymentProcessor(method: PaymentMethod): PaymentProcessor {
        val processor = newPaymentProcessor(method)
        check(processor.method == method && processor.currency == region.currency) {
            "Processor ${processor::class.simpleName} violates the region contract for $method/${region.code}"
        }
        return processor
    }

    protected abstract fun newPaymentProcessor(method: PaymentMethod): PaymentProcessor

    abstract fun createTaxCalculator(): TaxCalculator

    abstract fun createInvoiceFormatter(): InvoiceFormatter
}

/** Lookup of the configured regions. Adding a region = registering one more factory in the composition root. */
class RegionRegistry(factories: List<RegionFactory>) {
    private val byCode: Map<String, RegionFactory> = factories.associateBy { it.region.code }

    init {
        require(byCode.size == factories.size) { "Duplicate region codes" }
    }

    val supportedCodes: Set<String> get() = byCode.keys

    fun forRegion(code: String): RegionFactory = byCode[code] ?: throw UnsupportedRegionException(code)
}
