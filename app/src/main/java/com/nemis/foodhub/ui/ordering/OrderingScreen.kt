package com.nemis.foodhub.ui.ordering

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.domain.model.OrderType
import com.nemis.foodhub.domain.payment.PaymentMethod
import com.nemis.foodhub.presentation.CheckoutForm
import com.nemis.foodhub.presentation.CheckoutUiState
import com.nemis.foodhub.ui.OrderingViewModels
import java.time.Duration

/**
 * View only: renders the state of [MenuViewModel][com.nemis.foodhub.presentation.MenuViewModel],
 * [CartViewModel][com.nemis.foodhub.presentation.CartViewModel] and
 * [CheckoutViewModel][com.nemis.foodhub.presentation.CheckoutViewModel] and forwards user input to them.
 * The form fields below are raw input; the CheckoutViewModel turns them into a domain request. Prices, taxes, fees,
 * the region and all validation come from the domain.
 */
@Composable
fun OrderingScreen(
    vms: OrderingViewModels,
    onOrderPlaced: (OrderId) -> Unit,
    modifier: Modifier = Modifier
) {
    val menu by vms.menu.uiState.collectAsStateWithLifecycle()
    val cart by vms.cart.uiState.collectAsStateWithLifecycle()
    val checkout by vms.checkout.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (menu.selectedRestaurantId == null) menu.restaurantIds.firstOrNull()?.let(vms.menu::showRestaurant)
    }

    // Navigate once per placed order, then let the ViewModel return to Idle.
    LaunchedEffect(checkout) {
        (checkout as? CheckoutUiState.Success)?.let {
            onOrderPlaced(it.confirmation.orderId)
            vms.checkout.acknowledge()
        }
    }

    var type by rememberSaveable { mutableStateOf(OrderType.DELIVERY) }
    var method by rememberSaveable { mutableStateOf(PaymentMethod.CARD) }
    var payer by rememberSaveable { mutableStateOf("visa-4242") }
    var promo by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("12 Nguyen Hue, District 1") }
    var scheduleHours by rememberSaveable { mutableStateOf(2L) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("FoodHub", style = MaterialTheme.typography.headlineMedium) }

        // ------------------------------------------------------------ menu
        item { SectionTitle("Thực đơn") }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                menu.restaurants.forEach { restaurant ->
                    FilterChip(
                        selected = restaurant.id == menu.selectedRestaurantId,
                        onClick = { vms.menu.showRestaurant(restaurant.id) },
                        label = { Text("${restaurant.id} (${restaurant.regionCode ?: "?"})") }
                    )
                }
            }
        }
        items(menu.items, key = { "menu-" + it.id }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(item.price.toString(), style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(onClick = { vms.cart.addItem(item.id) }) { Text("Thêm") }
                }
            }
        }

        // ------------------------------------------------------------ cart
        item { SectionTitle("Giỏ hàng") }
        if (cart.lines.isEmpty()) {
            item { Text("Giỏ hàng trống", style = MaterialTheme.typography.bodyMedium) }
        }
        items(cart.lines, key = { "cart-" + it.itemId }) { line ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(line.name)
                    Text(line.lineTotal.toString(), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { vms.cart.setQuantity(line.itemId, line.quantity - 1) }) { Text("−") }
                Text("${line.quantity}")
                TextButton(onClick = { vms.cart.setQuantity(line.itemId, line.quantity + 1) }) { Text("+") }
                TextButton(onClick = { vms.cart.removeItem(line.itemId) }) { Text("Xóa") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vms.cart::undo, enabled = cart.canUndo) { Text("Hoàn tác") }
                OutlinedButton(onClick = vms.cart::restoreDraft, enabled = cart.canRestoreDraft) { Text("Khôi phục nháp") }
            }
            cart.error?.let { ErrorText(it) }
        }

        // ------------------------------------------------------------ checkout
        item { SectionTitle("Thanh toán") }
        item {
            ChipRow("Loại đơn", OrderType.entries, type, { type = it }) {
                when (it) {
                    OrderType.DELIVERY -> "Giao tận nơi"
                    OrderType.PICKUP -> "Tự đến lấy"
                    OrderType.SCHEDULED -> "Hẹn giờ"
                }
            }
        }
        if (type == OrderType.SCHEDULED) {
            item {
                ChipRow("Giao sau", listOf(1L, 2L, 24L), scheduleHours, { scheduleHours = it }) { "$it giờ" }
            }
        }
        if (type.requiresCourier) {
            item {
                OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("Địa chỉ giao hàng") })
            }
        }
        item {
            ChipRow("Phương thức", PaymentMethod.entries, method, { method = it }) {
                when (it) {
                    PaymentMethod.CARD -> "Thẻ"
                    PaymentMethod.WALLET -> "Ví điện tử"
                    PaymentMethod.COD -> "COD"
                }
            }
        }
        item {
            OutlinedTextField(
                payer, { payer = it }, Modifier.fillMaxWidth(),
                label = { Text("Mã người trả") },
                supportingText = { Text("Nhập ${vms.checkout.declinedPayerRef} để giả lập thanh toán bị từ chối") }
            )
        }
        item {
            OutlinedTextField(
                promo, { promo = it }, Modifier.fillMaxWidth(),
                label = { Text("Mã khuyến mãi") },
                supportingText = { Text("Thử SAVE10 hoặc WELCOME20") }
            )
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = cart.lines.isNotEmpty(),
                onClick = {
                    vms.checkout.submit(
                        CheckoutForm(
                            type = type,
                            paymentMethod = method,
                            payerRef = payer,
                            promoCode = promo,
                            deliveryAddress = address,
                            scheduleIn = if (type == OrderType.SCHEDULED) Duration.ofHours(scheduleHours) else null
                        )
                    )
                }
            ) { Text("Đặt món") }
            (checkout as? CheckoutUiState.Failed)?.let { ErrorText(it.message) }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Column {
        Spacer(Modifier.padding(top = 4.dp))
        Text(text, style = MaterialTheme.typography.titleLarge)
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
internal fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(label: String, options: List<T>, selected: T, onSelect: (T) -> Unit, text: (T) -> String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(text(option)) })
            }
        }
    }
}
