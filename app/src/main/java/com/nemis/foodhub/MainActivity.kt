package com.nemis.foodhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nemis.foodhub.domain.model.OrderId
import com.nemis.foodhub.ui.OrderScreenViewModels
import com.nemis.foodhub.ui.OrderingViewModels
import com.nemis.foodhub.ui.ordering.OrderingScreen
import com.nemis.foodhub.ui.theme.FoodHubTheme
import com.nemis.foodhub.ui.tracking.OrderTrackingScreen

/** Two demo screens: ordering (menu, cart, checkout) and the order screen (tracking, actions, invoice). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as FoodHubApplication
        setContent {
            FoodHubTheme {
                val ordering = viewModel { OrderingViewModels(app.container, app.customer.id) }
                val orderScreen = viewModel { OrderScreenViewModels(app.container) }
                var openOrderId by rememberSaveable { mutableStateOf<String?>(null) }

                fun closeOrder() {
                    orderScreen.release()
                    openOrderId = null
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Orders are in memory: after the process was killed, a restored id may point to nothing.
                    val id = openOrderId?.takeIf { app.container.orders.findById(OrderId(it)) != null }
                    if (id == null) {
                        OrderingScreen(
                            vms = ordering,
                            customer = app.customer,
                            regionOfRestaurant = app.regionOfRestaurant,
                            clock = app.container.clock,
                            onOrderPlaced = { openOrderId = it.value },
                            modifier = Modifier.padding(innerPadding)
                        )
                    } else {
                        BackHandler(onBack = ::closeOrder)
                        val orderId = OrderId(id)
                        val (tracking, invoice) = orderScreen.bind(orderId)
                        OrderTrackingScreen(
                            orderId = orderId,
                            tracking = tracking,
                            invoice = invoice,
                            cart = ordering.cart,
                            container = app.container,
                            customer = app.customer,
                            onBack = ::closeOrder,
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }
}
