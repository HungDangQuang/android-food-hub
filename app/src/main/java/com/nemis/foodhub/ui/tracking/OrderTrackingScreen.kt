package com.nemis.foodhub.ui.tracking

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nemis.foodhub.app.AppContainer
import com.nemis.foodhub.domain.DomainException
import com.nemis.foodhub.domain.invoice.InvoiceFormat
import com.nemis.foodhub.domain.model.Customer
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.pricing.PromoCode
import com.nemis.foodhub.presentation.CartViewModel
import com.nemis.foodhub.presentation.InvoiceUiState
import com.nemis.foodhub.presentation.InvoiceViewModel
import com.nemis.foodhub.presentation.OrderTrackingViewModel
import com.nemis.foodhub.ui.ordering.ErrorText
import com.nemis.foodhub.ui.ordering.SectionTitle

/**
 * View only. Which customer actions are shown comes from the capabilities computed by the order's State (C1);
 * the status updates arrive through the Observer (C2) subscription held by [OrderTrackingViewModel].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrderTrackingScreen(
    orderId: OrderId,
    tracking: OrderTrackingViewModel,
    invoice: InvoiceViewModel,
    cart: CartViewModel,
    container: AppContainer,
    customer: Customer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by tracking.uiState.collectAsStateWithLifecycle()
    val invoiceState by invoice.uiState.collectAsStateWithLifecycle()
    var simulatorError by remember { mutableStateOf<String?>(null) }
    var outboxVersion by remember { mutableIntStateOf(0) }

    /** Restaurant, courier and admin are other apps; here they are simulated by calling their domain services. */
    fun simulate(action: () -> Unit) {
        simulatorError = try {
            action()
            null
        } catch (e: DomainException) {
            e.message
        }
        outboxVersion++
    }

    // Simulated gateway outbox (data layer stand-in), re-read whenever the order or a simulated action changes.
    val sent = remember(state, outboxVersion) { container.sentMessages.all().takeLast(9).reversed() }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row {
                TextButton(onClick = onBack) { Text("← Đặt món") }
            }
            Text("Đơn $orderId", style = MaterialTheme.typography.headlineSmall)
        }

        // ------------------------------------------------------------ status (Observer)
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(state.status.name, style = MaterialTheme.typography.titleLarge)
                    Text(state.message)
                    if (state.canTrackCourier) Text("Shipper đang trên đường (vị trí giả lập)")
                }
            }
        }

        // ------------------------------------------------------------ customer actions (State capabilities)
        item { SectionTitle("Thao tác của khách") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.canEditItems) {
                    OutlinedButton(onClick = { tracking.editItems(cart.items); outboxVersion++ }) {
                        Text("Cập nhật món theo giỏ hàng hiện tại")
                    }
                }
                if (state.canCancel) {
                    val fee = if (state.cancellationFeePercent > 0) " (phí ${state.cancellationFeePercent}%)" else " (miễn phí)"
                    OutlinedButton(onClick = { tracking.cancel(); outboxVersion++ }) { Text("Hủy đơn$fee") }
                }
                if (state.canRequestRefund) {
                    OutlinedButton(onClick = tracking::requestRefund) { Text("Yêu cầu hoàn tiền") }
                }
                if (state.canRate) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Đánh giá:", Modifier.padding(top = 12.dp))
                        (1..5).forEach { stars -> TextButton(onClick = { tracking.rate(stars) }) { Text("$stars★") } }
                    }
                }
                val none = !(state.canEditItems || state.canCancel || state.canRequestRefund || state.canRate)
                if (none) Text("Không có thao tác nào ở trạng thái này", style = MaterialTheme.typography.bodyMedium)
                state.error?.let { ErrorText(it) }
            }
        }

        // ------------------------------------------------------------ simulated other actors
        item { SectionTitle("Giả lập nhà hàng / shipper / admin") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { simulate { container.lifecycle.confirm(orderId) } }) { Text("Xác nhận") }
                Button(onClick = { simulate { container.lifecycle.startPreparing(orderId) } }) { Text("Chuẩn bị") }
                Button(onClick = { simulate { container.lifecycle.startDelivery(orderId) } }) { Text("Giao hàng") }
                Button(onClick = { simulate { container.lifecycle.markDelivered(orderId) } }) { Text("Đã giao") }
            }
            OutlinedButton(
                modifier = Modifier.padding(top = 8.dp),
                onClick = { simulate { container.promoAnnouncer.announce(PromoCode("SAVE10", 10), listOf(customer)) } }
            ) { Text("Admin: gửi khuyến mãi SAVE10") }
            simulatorError?.let { ErrorText(it) }
        }

        // ------------------------------------------------------------ invoice
        item { SectionTitle("Hóa đơn") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InvoiceFormat.entries.forEach { format ->
                    OutlinedButton(onClick = { invoice.export(format) }) { Text("Xuất $format") }
                }
            }
            when (val s = invoiceState) {
                is InvoiceUiState.Ready -> Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(
                        s.content,
                        Modifier.padding(12.dp).horizontalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                is InvoiceUiState.Failed -> ErrorText(s.message)
                InvoiceUiState.Idle -> Unit
            }
        }

        // ------------------------------------------------------------ notifications (Bridge)
        item { SectionTitle("Thông báo đã gửi") }
        items(sent) { message ->
            Column {
                val tag = if (message.urgent) "${message.channel} · KHẨN" else message.channel.toString()
                Text("$tag → ${message.to}", style = MaterialTheme.typography.labelMedium)
                Text(listOf(message.title, message.body).filter { it.isNotEmpty() }.joinToString(": "), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
