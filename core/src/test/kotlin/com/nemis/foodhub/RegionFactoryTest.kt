package com.nemis.foodhub

import com.nemis.foodhub.app.AppContainer
import com.nemis.foodhub.domain.PaymentFailedException
import com.nemis.foodhub.domain.UnsupportedRegionException
import com.nemis.foodhub.domain.checkout.CheckoutRequest
import com.nemis.foodhub.domain.invoice.BaseInvoiceFormatter
import com.nemis.foodhub.domain.invoice.Invoice
import com.nemis.foodhub.domain.invoice.InvoiceFormat
import com.nemis.foodhub.domain.invoice.InvoiceFormatter
import com.nemis.foodhub.domain.invoice.InvoiceLabels
import com.nemis.foodhub.domain.model.Address
import com.nemis.foodhub.domain.model.CartItem
import com.nemis.foodhub.domain.model.Currency
import com.nemis.foodhub.domain.model.MenuItem
import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.model.OrderStatus
import com.nemis.foodhub.domain.model.OrderType
import com.nemis.foodhub.domain.payment.PaymentMethod
import com.nemis.foodhub.domain.payment.PaymentProcessor
import com.nemis.foodhub.domain.payment.PaymentRequest
import com.nemis.foodhub.domain.payment.Refundable
import com.nemis.foodhub.domain.payment.SimulatedPaymentProcessor
import com.nemis.foodhub.domain.payment.SimulatedRefundableProcessor
import com.nemis.foodhub.domain.pricing.TaxCalculator
import com.nemis.foodhub.domain.region.Region
import com.nemis.foodhub.domain.region.RegionFactory
import com.nemis.foodhub.domain.region.RegionRegistry
import com.nemis.foodhub.domain.region.us.UsRegionFactory
import com.nemis.foodhub.domain.region.vn.VietnamRegionFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** C3: Abstract Factory (+ Factory Method for PaymentProcessor), and OCP/LSP evidence. */
class RegionFactoryTest {

    private val vn = VietnamRegionFactory()
    private val us = UsRegionFactory()

    // ---- each factory creates a consistent family ----

    @Test
    fun vietnamFamilyUsesVndVatAndVietnameseInvoice() {
        assertEquals(Currency.VND, vn.region.currency)
        assertEquals(Money.of(11_000, Currency.VND), vn.createTaxCalculator().taxFor(Money.of(110_000, Currency.VND)))
        val html = vn.createInvoiceFormatter().format(sampleInvoice(vn), InvoiceFormat.HTML)
        assertTrue(html.contains("Hóa đơn"))
        assertTrue(html.contains("VND"))
        assertTrue(vn.createPaymentProcessor(PaymentMethod.WALLET).pay(request(vn, PaymentMethod.WALLET)).transactionId.startsWith("MoMo"))
    }

    @Test
    fun usFamilyUsesUsdSalesTaxAndEnglishInvoice() {
        assertEquals(Currency.USD, us.region.currency)
        assertEquals(Money.of("0.70", Currency.USD), us.createTaxCalculator().taxFor(Money.of("10.00", Currency.USD)))
        val csv = us.createInvoiceFormatter().format(sampleInvoice(us), InvoiceFormat.CSV)
        assertTrue(csv.contains("\"Subtotal\""))
        assertTrue(csv.contains("$"))
        assertTrue(us.createPaymentProcessor(PaymentMethod.CARD).pay(request(us, PaymentMethod.CARD)).transactionId.startsWith("Stripe"))
    }

    @Test
    fun sameOrderIsPricedAndInvoicedDifferentlyPerRegion() {
        val vnApp = Fixtures.newApp()
        val vnOrder = vnApp.order(vnApp.place().orderId)
        val usApp = Fixtures.newApp()
        val usOrder = usApp.order(
            usApp.facade.checkout(
                CheckoutRequest(
                    Fixtures.customer, listOf(CartItem(usApp.menu.findItem("cheeseburger")!!, 2)), OrderType.DELIVERY, "US",
                    PaymentMethod.CARD, "visa", deliveryAddress = Address("1 Main St")
                )
            ).orderId
        )
        assertEquals(Currency.VND, vnOrder.price.total.currency)
        assertEquals(Currency.USD, usOrder.price.total.currency)
        // 17.98 + 3.99 fee + 7% of 17.98 (1.26) = 23.23
        assertEquals(Money.of("23.23", Currency.USD), usOrder.price.total)
    }

    // ---- LSP: every processor created by any factory honours the same contract ----

    @Test
    fun everyProcessorHonoursTheSamePreAndPostConditions() {
        for (factory in listOf(vn, us)) {
            for (method in PaymentMethod.values()) {
                val processor = factory.createPaymentProcessor(method)
                assertEquals(method, processor.method)
                assertEquals(factory.region.currency, processor.currency)

                val receipt = processor.pay(request(factory, method))
                assertEquals("post: receipt.amount == request.amount", Money.of(100, factory.region.currency), receipt.amount)

                assertThrows("zero amount", IllegalArgumentException::class.java) {
                    processor.pay(PaymentRequest("o", Money.zero(factory.region.currency), method, "p"))
                }
                assertThrows("wrong currency", IllegalArgumentException::class.java) {
                    processor.pay(PaymentRequest("o", Money.of(100, otherCurrency(factory)), method, "p"))
                }
                val otherMethod = PaymentMethod.values().first { it != method }
                assertThrows("wrong method", IllegalArgumentException::class.java) {
                    processor.pay(PaymentRequest("o", Money.of(100, factory.region.currency), otherMethod, "p"))
                }
            }
        }
    }

    @Test
    fun declinedPaymentsAreSignalledByExceptionForOnlinePaymentsOnly() {
        for (method in listOf(PaymentMethod.CARD, PaymentMethod.WALLET)) {
            assertThrows(PaymentFailedException::class.java) {
                vn.createPaymentProcessor(method).pay(request(vn, method, payer = SimulatedPaymentProcessor.DECLINED_PAYER))
            }
        }
        // COD collects nothing at checkout, so there is nothing to decline.
        val receipt = vn.createPaymentProcessor(PaymentMethod.COD)
            .pay(request(vn, PaymentMethod.COD, payer = SimulatedPaymentProcessor.DECLINED_PAYER))
        assertFalse(receipt.collected)
    }

    // ---- ISP: only online methods can refund ----

    @Test
    fun onlyCardAndWalletAreRefundable() {
        assertTrue(vn.createPaymentProcessor(PaymentMethod.CARD) is Refundable)
        assertTrue(vn.createPaymentProcessor(PaymentMethod.WALLET) is Refundable)
        assertFalse(vn.createPaymentProcessor(PaymentMethod.COD) is Refundable)
    }

    @Test
    fun refundMustNotExceedWhatWasPaid() {
        val processor = vn.createPaymentProcessor(PaymentMethod.CARD)
        val receipt = processor.pay(request(vn, PaymentMethod.CARD))
        val refundable = processor as Refundable
        assertEquals(Money.of(40, Currency.VND), refundable.refund(receipt, Money.of(40, Currency.VND)).refundedAmount)
        assertThrows(IllegalArgumentException::class.java) { refundable.refund(receipt, Money.of(101, Currency.VND)) }
        assertThrows(IllegalArgumentException::class.java) { refundable.refund(receipt, Money.zero(Currency.VND)) }
    }

    // ---- registry and Factory Method contract ----

    @Test
    fun unknownRegionIsRejected() {
        assertThrows(UnsupportedRegionException::class.java) { RegionRegistry(listOf(vn)).forRegion("FR") }
        assertEquals(setOf("VN", "US"), RegionRegistry(listOf(vn, us)).supportedCodes)
    }

    @Test
    fun duplicateRegionCodesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { RegionRegistry(listOf(vn, VietnamRegionFactory())) }
    }

    @Test
    fun factoryMethodRejectsAProcessorThatBreaksTheRegionContract() {
        val broken = object : RegionFactory(VietnamRegionFactory.REGION) {
            override fun newPaymentProcessor(method: PaymentMethod): PaymentProcessor =
                SimulatedRefundableProcessor(method, Currency.USD, "WrongCurrency")

            override fun createTaxCalculator() = VietnamRegionFactory().createTaxCalculator()
            override fun createInvoiceFormatter() = VietnamRegionFactory().createInvoiceFormatter()
        }
        assertThrows(IllegalStateException::class.java) { broken.createPaymentProcessor(PaymentMethod.CARD) }
    }

    // ---- OCP: a brand new region without modifying a single existing class ----

    @Test
    fun aNewRegionWithItsOwnCurrencyWorksEndToEndWithoutChangingExistingCode() {
        val sushi = MenuItem("sushi", "r-jp", "Sushi set", Money.of(1_200, JapanRegionFactory.JPY))
        val app = AppContainer(
            regionFactories = listOf(VietnamRegionFactory(), UsRegionFactory(), JapanRegionFactory()),
            initialStock = mapOf("sushi" to 10)
        )

        val confirmation = app.facade.checkout(
            CheckoutRequest(
                Fixtures.customer, listOf(CartItem(sushi, 2)), OrderType.DELIVERY, "JP", PaymentMethod.CARD, "jcb-1",
                deliveryAddress = Address("1-1 Chiyoda, Tokyo")
            )
        )

        // 2 x 1200 = 2400; fee 500; 10% consumption tax on 2400 = 240; total 3140
        assertEquals(Money.of(3_140, JapanRegionFactory.JPY), confirmation.price.total)
        assertEquals(OrderStatus.CREATED, confirmation.status)

        val invoice = JapanRegionFactory().createInvoiceFormatter().format(Invoice.from(app.order(confirmation.orderId), app.clock.instant()), InvoiceFormat.CSV)
        assertTrue(invoice.contains("3140 JPY"))

        val cancel = app.facade.cancelOrder(confirmation.orderId)
        assertEquals(Money.of(3_140, JapanRegionFactory.JPY), cancel.refund)
    }

    // ---- helpers ----

    private fun request(factory: RegionFactory, method: PaymentMethod, payer: String = "payer") =
        PaymentRequest("o-1", Money.of(100, factory.region.currency), method, payer)

    private fun otherCurrency(factory: RegionFactory) =
        if (factory.region.currency == Currency.VND) Currency.USD else Currency.VND

    private fun sampleInvoice(factory: RegionFactory): Invoice {
        val app = Fixtures.newApp()
        val item = if (factory.region.code == "VN") app.menu.findItem("pho-bo")!! else app.menu.findItem("cheeseburger")!!
        val confirmation = app.facade.checkout(
            CheckoutRequest(
                Fixtures.customer, listOf(CartItem(item, 1)), OrderType.DELIVERY, factory.region.code,
                PaymentMethod.CARD, "visa", deliveryAddress = Address("somewhere")
            )
        )
        return Invoice.from(app.order(confirmation.orderId), app.clock.instant())
    }
}

/** A region defined entirely in test code: it adds a currency, tax rule and invoice format. */
class JapanRegionFactory : RegionFactory(REGION) {
    override fun newPaymentProcessor(method: PaymentMethod): PaymentProcessor = when (method) {
        PaymentMethod.CARD -> SimulatedRefundableProcessor(method, JPY, "JCB")
        PaymentMethod.WALLET -> SimulatedRefundableProcessor(method, JPY, "PayPay")
        PaymentMethod.COD -> SimulatedPaymentProcessor(method, JPY, "COD-JP", collectsAtCheckout = false)
    }

    override fun createTaxCalculator() = object : TaxCalculator {
        override fun taxFor(taxableAmount: Money) = taxableAmount.percent(10)
    }

    override fun createInvoiceFormatter(): InvoiceFormatter = object : BaseInvoiceFormatter() {
        override val labels = InvoiceLabels(
            "Invoice", "Order", "Customer", "Date", "Payment", "Item", "Qty", "Unit", "Line", "Subtotal",
            "Discount", "Delivery", "Consumption tax", "Total"
        )

        override fun money(value: Money) = "${value.amount.toPlainString()} JPY"
    }

    companion object {
        val JPY = Currency("JPY", 0)
        val REGION = Region("JP", JPY, Money.of(500, JPY))
    }
}
