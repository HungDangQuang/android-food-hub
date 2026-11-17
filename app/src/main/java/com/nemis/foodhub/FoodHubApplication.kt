package com.nemis.foodhub

import android.app.Application
import com.nemis.foodhub.app.AppContainer
import com.nemis.foodhub.domain.model.ChannelType
import com.nemis.foodhub.domain.model.Customer
import java.io.File

/**
 * Holds the composition root for the lifetime of the process. All wiring stays in [AppContainer] (module :core);
 * the Android layer only adds what a real app would get from the platform and from login.
 */
class FoodHubApplication : Application() {

    /** Cart drafts go to the app's private files dir, so they survive the process being killed. */
    val container: AppContainer by lazy { AppContainer(draftDirectory = File(filesDir, "cart-drafts")) }

    /** Stand-in for the signed-in customer. */
    val customer = Customer(
        id = "c-1",
        name = "Hung",
        phone = "+84900000001",
        email = "hung@example.com",
        channels = setOf(ChannelType.PUSH, ChannelType.SMS, ChannelType.EMAIL)
    )
}
