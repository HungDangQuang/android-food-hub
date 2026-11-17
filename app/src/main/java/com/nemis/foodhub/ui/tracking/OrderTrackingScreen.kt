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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nemis.foodhub.domain.invoice.InvoiceFormat
import com.nemis.foodhub.presentation.InvoiceUiState
import com.nemis.foodhub.presentation.OrderNotice
import com.nemis.foodhub.ui.OrderScreen
import com.nemis.foodhub.ui.ordering.ErrorText
import com.nemis.foodhub.ui.ordering.SectionTitle

/**
 * View only. Which customer actions are shown comes from the capabilities computed by the order's State (C1);
 * status updates arrive through the Observer (C2) subscription held by the tracking ViewModel. The simulated
 * restaurant/courier/admin buttons and the outbox go through their own ViewModels as well.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrderTrackingScreen(
    screen: OrderScreen,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by screen.tracking.uiState.collectAsStateWithLifecycle()
    val invoiceState by screen.invoice.uiState.collectAsStateWithLifecycle()
    val simulator by screen.simulator.uiState.collectAsStateWithLifecycle()
    val outbox by screen.notifications.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row {
                TextButton(onClick = onBack) { Text("← Đặt món") }
            }
            Text("Đơn ${screen.orderId}", style = MaterialTheme.typography.headlineSmall)
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

        // ------------------------------------------------------------ items (editable while the State allows it)
        item { SectionTitle("Món trong đơn") }
        items(state.lines, key = { "line-" + it.itemId }) { line ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(line.name)
                    Text(line.lineTotal.toString(), style = MaterialTheme.typography.bodySmall)
                }
                if (state.canEditItems) {
                    TextButton(onClick = { screen.tracking.setItemQuantity(line.itemId, line.quantity - 1) }) { Text("−") }
                }
                Text("x${line.quantity}")
                if (state.canEditItems) {
                    TextButton(onClick = { screen.tracking.setItemQuantity(line.itemId, line.quantity + 1) }) { Text("+") }
                }
            }
        }
        item { state.total?.let { Text("Tổng cộng: $it", style = MaterialTheme.typography.titleMedium) } }

        // ------------------------------------------------------------ customer actions (State capabilities)
        item { SectionTitle("Thao tác của khách") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.canCancel) {
                    val fee = if (state.cancellationFeePercent > 0) " (phí ${state.cancellationFeePercent}%)" else " (miễn phí)"
                    OutlinedButton(onClick = screen.tracking::cancel) { Text("Hủy đơn$fee") }
                }
                if (state.canRequestRefund) {
                    OutlinedButton(onClick = screen.tracking::requestRefund) { Text("Yêu cầu hoàn tiền") }
                }
                if (state.canRate) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Đánh giá:", Modifier.padding(top = 12.dp))
                        (1..5).forEach { stars -> TextButton(onClick = { screen.tracking.rate(stars) }) { Text("$stars★") } }
                    }
                }
                val none = !(state.canEditItems || state.canCancel || state.canRequestRefund || state.canRate)
                if (none) Text("Không có thao tác nào ở trạng thái này", style = MaterialTheme.typography.bodyMedium)
                state.notice?.let { Text(describe(it), color = MaterialTheme.colorScheme.primary) }
                state.error?.let { ErrorText(it) }
            }
        }

        // ------------------------------------------------------------ simulated other actors
        item { SectionTitle("Giả lập nhà hàng / shipper / admin") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = screen.simulator::confirm) { Text("Xác nhận") }
                Button(onClick = screen.simulator::startPreparing) { Text("Chuẩn bị") }
                Button(onClick = screen.simulator::startDelivery) { Text("Giao hàng") }
                Button(onClick = screen.simulator::markDelivered) { Text("Đã giao") }
            }
            OutlinedButton(
                modifier = Modifier.padding(top = 8.dp),
                onClick = { screen.simulator.announcePromo("SAVE10") }
            ) { Text("Admin: gửi khuyến mãi SAVE10") }
            simulator.error?.let { ErrorText(it) }
        }

        // ------------------------------------------------------------ invoice
        item { SectionTitle("Hóa đơn") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InvoiceFormat.entries.forEach { format ->
                    OutlinedButton(onClick = { screen.invoice.export(format) }) { Text("Xuất $format") }
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
        items(outbox.messages) { message ->
            Column {
                val tag = if (message.urgent) "${message.channel} · KHẨN" else message.channel.toString()
                Text("$tag → ${message.to}", style = MaterialTheme.typography.labelMedium)
                Text(listOf(message.title, message.body).filter { it.isNotEmpty() }.joinToString(": "), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Display text only; the amounts were decided by the domain. */
private fun describe(notice: OrderNotice): String = when (notice) {
    is OrderNotice.Cancelled -> "Đã hủy đơn. Phí hủy: ${notice.fee}, hoàn lại: ${notice.refund}"
    is OrderNotice.RefundRequested -> "Đã yêu cầu hoàn ${notice.amount}"
    is OrderNotice.ItemsUpdated -> "Đã cập nhật món. Tổng mới: ${notice.total}"
    is OrderNotice.Rated -> "Cảm ơn bạn đã đánh giá ${notice.stars}★"
}
