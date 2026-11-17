package com.nemis.foodhub.domain.region.vn

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

class VietnamRegionFactory : RegionFactory(REGION) {

    override fun newPaymentProcessor(method: PaymentMethod): PaymentProcessor = when (method) {
        PaymentMethod.CARD -> SimulatedRefundableProcessor(method, Currency.VND, "VNPay")
        PaymentMethod.WALLET -> SimulatedRefundableProcessor(method, Currency.VND, "MoMo")
        PaymentMethod.COD -> SimulatedPaymentProcessor(method, Currency.VND, "COD-VN", collectsAtCheckout = false)
    }

    override fun createTaxCalculator(): TaxCalculator = VietnamVatCalculator()

    override fun createInvoiceFormatter(): InvoiceFormatter = VietnamInvoiceFormatter()

    companion object {
        const val CODE = "VN"
        val REGION = Region(CODE, Currency.VND, Money.of(15_000, Currency.VND))
    }
}

/** Simulated rule: flat 10% VAT. */
class VietnamVatCalculator : TaxCalculator {
    override fun taxFor(taxableAmount: Money): Money = taxableAmount.percent(BigDecimal(10))
}

class VietnamInvoiceFormatter : BaseInvoiceFormatter() {
    override val labels = InvoiceLabels(
        title = "Hóa đơn bán hàng", order = "Mã đơn", customer = "Khách hàng", date = "Ngày", payment = "Thanh toán",
        item = "Món", quantity = "SL", unitPrice = "Đơn giá", lineTotal = "Thành tiền",
        subtotal = "Tạm tính", discount = "Giảm giá", deliveryFee = "Phí giao hàng",
        tax = "Thuế VAT", total = "Tổng cộng"
    )

    override fun money(value: Money): String {
        val symbols = DecimalFormatSymbols(Locale.ROOT).apply { groupingSeparator = '.' }
        return DecimalFormat("#,##0", symbols).format(value.amount) + " VND"
    }
}
