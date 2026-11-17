package com.nemis.foodhub.domain.invoice

import com.nemis.foodhub.domain.model.Money
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.order.Order
import com.nemis.foodhub.domain.payment.PaymentMethod
import com.nemis.foodhub.domain.pricing.PriceBreakdown
import com.nemis.foodhub.domain.region.Region
import java.time.Instant

data class InvoiceLine(val name: String, val quantity: Int, val unitPrice: Money, val lineTotal: Money)

data class Invoice(
    val orderId: OrderId,
    val issuedAt: Instant,
    val customerName: String,
    val region: Region,
    val paymentMethod: PaymentMethod,
    val lines: List<InvoiceLine>,
    val price: PriceBreakdown
) {
    companion object {
        fun from(order: Order, issuedAt: Instant): Invoice = Invoice(
            orderId = order.id,
            issuedAt = issuedAt,
            customerName = order.customer.name,
            region = order.region,
            paymentMethod = order.paymentMethod,
            lines = order.items.map { InvoiceLine(it.item.name, it.quantity, it.item.price, it.lineTotal) },
            price = order.price
        )
    }
}

enum class InvoiceFormat { HTML, CSV }

/** Part of the regional product family created by RegionFactory. */
interface InvoiceFormatter {
    fun format(invoice: Invoice, format: InvoiceFormat): String
}

data class InvoiceLabels(
    val title: String,
    val order: String,
    val customer: String,
    val date: String,
    val payment: String,
    val item: String,
    val quantity: String,
    val unitPrice: String,
    val lineTotal: String,
    val subtotal: String,
    val discount: String,
    val deliveryFee: String,
    val tax: String,
    val total: String
)

/** Shared layout for HTML/CSV; a region only supplies its labels and its money notation. */
abstract class BaseInvoiceFormatter : InvoiceFormatter {

    protected abstract val labels: InvoiceLabels
    protected abstract fun money(value: Money): String

    final override fun format(invoice: Invoice, format: InvoiceFormat): String = when (format) {
        InvoiceFormat.HTML -> toHtml(invoice)
        InvoiceFormat.CSV -> toCsv(invoice)
    }

    private fun toHtml(invoice: Invoice): String {
        val l = labels
        val rows = invoice.lines.joinToString("\n") {
            "<tr><td>${esc(it.name)}</td><td>${it.quantity}</td><td>${money(it.unitPrice)}</td><td>${money(it.lineTotal)}</td></tr>"
        }
        val p = invoice.price
        return """
            |<html><body>
            |<h1>${esc(l.title)}</h1>
            |<p>${esc(l.order)}: ${esc(invoice.orderId.value)}<br/>${esc(l.customer)}: ${esc(invoice.customerName)}<br/>${esc(l.date)}: ${invoice.issuedAt}<br/>${esc(l.payment)}: ${invoice.paymentMethod}</p>
            |<table border="1">
            |<tr><th>${esc(l.item)}</th><th>${esc(l.quantity)}</th><th>${esc(l.unitPrice)}</th><th>${esc(l.lineTotal)}</th></tr>
            |$rows
            |</table>
            |<p>${esc(l.subtotal)}: ${money(p.subtotal)}<br/>${esc(l.discount)}: ${money(p.discount)}<br/>${esc(l.deliveryFee)}: ${money(p.deliveryFee)}<br/>${esc(l.tax)}: ${money(p.tax)}<br/><b>${esc(l.total)}: ${money(p.total)}</b></p>
            |</body></html>
        """.trimMargin()
    }

    private fun toCsv(invoice: Invoice): String {
        val l = labels
        val p = invoice.price
        val out = StringBuilder()
        out.append(csv(l.item, l.quantity, l.unitPrice, l.lineTotal)).append('\n')
        invoice.lines.forEach {
            out.append(csv(it.name, it.quantity.toString(), money(it.unitPrice), money(it.lineTotal))).append('\n')
        }
        out.append(csv(l.subtotal, "", "", money(p.subtotal))).append('\n')
        out.append(csv(l.discount, "", "", money(p.discount))).append('\n')
        out.append(csv(l.deliveryFee, "", "", money(p.deliveryFee))).append('\n')
        out.append(csv(l.tax, "", "", money(p.tax))).append('\n')
        out.append(csv(l.total, "", "", money(p.total))).append('\n')
        return out.toString()
    }

    private fun csv(vararg cells: String): String =
        cells.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }

    private fun esc(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
