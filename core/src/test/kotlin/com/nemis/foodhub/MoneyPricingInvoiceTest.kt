package com.nemis.foodhub

import com.nemis.foodhub.domain.ValidationException
import com.nemis.foodhub.domain.invoice.Invoice
import com.nemis.foodhub.domain.invoice.InvoiceFormat
import com.nemis.foodhub.domain.model.Address
import com.nemis.foodhub.domain.model.CartItem
import com.nemis.foodhub.domain.model.Currency
import com.nemis.foodhub.domain.model.MenuItem
import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.model.OrderType
import com.nemis.foodhub.domain.checkout.CheckoutRequest
import com.nemis.foodhub.domain.payment.PaymentMethod
import com.nemis.foodhub.domain.pricing.PricingService
import com.nemis.foodhub.domain.pricing.PromoCode
import com.nemis.foodhub.domain.region.vn.VietnamRegionFactory
import com.nemis.foodhub.domain.region.us.UsRegionFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyPricingInvoiceTest {

    // ---- Money ----

    @Test
    fun amountsAreRoundedToTheCurrencyPrecision() {
        assertEquals("10.01", Money.of("10.005", Currency.USD).amount.toPlainString())
        assertEquals("1001", Money.of("1000.5", Currency.VND).amount.toPlainString())
        assertEquals(Money.of(12_346, Currency.VND), Money.of(123_456, Currency.VND).percent(10))
    }

    @Test
    fun equalMoneyHasEqualHashAndOrdering() {
        assertEquals(Money.of("1.0", Currency.USD), Money.of("1.00", Currency.USD))
        assertEquals(Money.of("1.0", Currency.USD).hashCode(), Money.of("1.00", Currency.USD).hashCode())
        assertNotEquals(Money.of(1, Currency.USD), Money.of(1, Currency.VND))
        assertTrue(Money.of(2, Currency.VND) > Money.of(1, Currency.VND))
        assertEquals("1000 VND", Money.of(1000, Currency.VND).toString())
    }

    @Test
    fun mixingCurrenciesIsAnError() {
        assertThrows(IllegalArgumentException::class.java) { Money.of(1, Currency.VND) + Money.of(1, Currency.USD) }
        assertThrows(IllegalArgumentException::class.java) { Money.of(1, Currency.VND) - Money.of(1, Currency.USD) }
        assertThrows(IllegalArgumentException::class.java) { Money.of(1, Currency.VND).compareTo(Money.of(1, Currency.USD)) }
    }

    @Test
    fun zeroAndSignHelpers() {
        assertTrue(Money.zero(Currency.USD).isZero)
        assertFalse(Money.zero(Currency.USD).isPositive)
        assertTrue((Money.of(5, Currency.VND) - Money.of(2, Currency.VND)).isPositive)
        assertEquals(Money.of(15, Currency.VND), Money.of(5, Currency.VND) * 3)
    }

    // ---- Pricing ----

    private val tax = VietnamRegionFactory().createTaxCalculator()
    private val pho = MenuItem("pho", "r", "Pho", Money.of(55_000, Currency.VND))

    @Test
    fun deliveryFeeIsNotTaxedAndDiscountReducesTheTaxableAmount() {
        val price = PricingService().price(listOf(CartItem(pho, 2)), PromoCode("P", 10), Money.of(15_000, Currency.VND), tax)
        assertEquals(Money.of(110_000, Currency.VND), price.subtotal)
        assertEquals(Money.of(11_000, Currency.VND), price.discount)
        assertEquals(Money.of(9_900, Currency.VND), price.tax)
        assertEquals(Money.of(123_900, Currency.VND), price.total)
    }

    @Test
    fun emptyCartCannotBePriced() {
        assertThrows(ValidationException::class.java) {
            PricingService().price(emptyList(), null, Money.zero(Currency.VND), tax)
        }
    }

    @Test
    fun promoPercentageMustBeBetween1And100() {
        assertThrows(IllegalArgumentException::class.java) { PromoCode("X", 0) }
        assertThrows(IllegalArgumentException::class.java) { PromoCode("X", 101) }
        PromoCode("OK", 100)
    }

    // ---- Invoices ----

    private fun vnOrder(name: String = "Hung"): com.nemis.foodhub.domain.order.Order {
        val app = Fixtures.newApp()
        val request = Fixtures.vnRequest(app).copy(customer = Fixtures.customer.copy(name = name))
        return app.order(app.facade.checkout(request).orderId).also { }
    }

    @Test
    fun htmlInvoiceEscapesCustomerInput() {
        val order = vnOrder("<script>alert(1)</script> & \"Co\"")
        val html = VietnamRegionFactory().createInvoiceFormatter().format(Invoice.from(order, order.history.firstOrNull()?.at ?: java.time.Instant.EPOCH), InvoiceFormat.HTML)
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("&amp;"))
        assertTrue(html.contains("136.000 VND"))
    }

    @Test
    fun csvInvoiceHasHeaderLinesAndTotals() {
        val order = vnOrder("Nguyen, \"Van\" A")
        val csv = VietnamRegionFactory().createInvoiceFormatter().format(Invoice.from(order, java.time.Instant.EPOCH), InvoiceFormat.CSV)
        val lines = csv.trim().lines()
        assertEquals(1 + 1 + 5, lines.size) // header, one item, subtotal/discount/fee/tax/total
        assertTrue(lines.first().contains("Món"))
        assertTrue(lines.last().contains("136.000 VND"))
    }

    @Test
    fun csvEscapesQuotesInCells() {
        val app = Fixtures.newApp()
        val quoted = MenuItem("pho-bo", "r-vn-pho", "Pho \"special\", large", Money.of(55_000, Currency.VND))
        val confirmation = app.facade.checkout(
            CheckoutRequest(Fixtures.customer, listOf(CartItem(quoted, 1)), OrderType.DELIVERY, "VN", PaymentMethod.CARD, "visa",
                deliveryAddress = Address("x"))
        )
        val csv = VietnamRegionFactory().createInvoiceFormatter()
            .format(Invoice.from(app.order(confirmation.orderId), java.time.Instant.EPOCH), InvoiceFormat.CSV)
        assertTrue(csv.contains("\"Pho \"\"special\"\", large\""))
    }

    @Test
    fun usInvoiceUsesDollarsWithTwoDecimals() {
        val app = Fixtures.newApp()
        val confirmation = app.facade.checkout(
            CheckoutRequest(Fixtures.customer, listOf(CartItem(app.menu.findItem("cheeseburger")!!, 1)), OrderType.PICKUP, "US",
                PaymentMethod.CARD, "visa")
        )
        val html = UsRegionFactory().createInvoiceFormatter()
            .format(Invoice.from(app.order(confirmation.orderId), java.time.Instant.EPOCH), InvoiceFormat.HTML)
        assertTrue(html.contains("$8.99"))
        assertTrue(html.contains("Invoice"))
    }
}
