package com.nemis.foodhub.domain.payment

import com.nemis.foodhub.domain.PaymentFailedException
import com.nemis.foodhub.domain.model.Currency
import com.nemis.foodhub.domain.model.Money

enum class PaymentMethod { CARD, WALLET, COD }

data class PaymentRequest(
    val orderRef: String,
    val amount: Money,
    val method: PaymentMethod,
    val payerRef: String
)

/** [collected] is false for COD: nothing has been taken yet, cash is due on delivery. */
data class PaymentReceipt(
    val transactionId: String,
    val method: PaymentMethod,
    val amount: Money,
    val collected: Boolean
)

data class RefundReceipt(val transactionId: String, val refundedAmount: Money)

/**
 * ISP: capabilities are split so a processor only promises what it can really do.
 * Cash on delivery can be paid but cannot be refunded online, so it implements [Payable] only.
 */
interface Payable {
    /**
     * Pre: amount > 0, amount.currency == processor currency, request.method == processor method.
     * Post: receipt.amount == request.amount. Failure is signalled by [PaymentFailedException], never null.
     */
    fun pay(request: PaymentRequest): PaymentReceipt
}

interface Refundable {
    /**
     * Pre: receipt was issued by the same method and collected, 0 < amount <= receipt.amount.
     * Post: refundedAmount == amount.
     */
    fun refund(receipt: PaymentReceipt, amount: Money): RefundReceipt
}

interface PaymentProcessor : Payable {
    val method: PaymentMethod
    val currency: Currency
}

/**
 * Stand-in for a real gateway adapter (no I/O). A payer reference of [DECLINED_PAYER] simulates a declined payment.
 * Real gateways would be injected through the constructor from the data layer.
 */
open class SimulatedPaymentProcessor(
    final override val method: PaymentMethod,
    final override val currency: Currency,
    protected val gateway: String,
    private val collectsAtCheckout: Boolean = true
) : PaymentProcessor {

    final override fun pay(request: PaymentRequest): PaymentReceipt {
        require(request.amount.isPositive) { "amount must be positive" }
        require(request.amount.currency == currency) { "processor handles $currency only" }
        require(request.method == method) { "processor handles $method only" }
        if (collectsAtCheckout && request.payerRef == DECLINED_PAYER) {
            throw PaymentFailedException("$gateway declined the payment")
        }
        return PaymentReceipt("$gateway-${request.orderRef}", method, request.amount, collectsAtCheckout)
    }

    companion object {
        const val DECLINED_PAYER = "DECLINED"
    }
}

class SimulatedRefundableProcessor(method: PaymentMethod, currency: Currency, gateway: String) :
    SimulatedPaymentProcessor(method, currency, gateway), Refundable {

    override fun refund(receipt: PaymentReceipt, amount: Money): RefundReceipt {
        require(receipt.method == method) { "receipt was issued by ${receipt.method}" }
        require(receipt.collected) { "nothing was collected for this receipt" }
        require(amount.isPositive && amount <= receipt.amount) { "invalid refund amount $amount" }
        return RefundReceipt("$gateway-REFUND-${receipt.transactionId}", amount)
    }
}
