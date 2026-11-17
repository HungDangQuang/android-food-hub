package com.nemis.foodhub

import android.app.Application
import com.nemis.foodhub.app.AppContainer
import com.nemis.foodhub.data.InMemoryMenuRepository
import com.nemis.foodhub.domain.model.ChannelType
import com.nemis.foodhub.domain.model.Customer
import com.nemis.foodhub.domain.region.us.UsRegionFactory
import com.nemis.foodhub.domain.region.vn.VietnamRegionFactory

/**
 * Holds the composition root for the lifetime of the process. All wiring stays in [AppContainer] (module :core);
 * the Android layer only adds what a real app would get from login and restaurant data.
 */
class FoodHubApplication : Application() {

    val container: AppContainer by lazy { AppContainer() }

    /** Stand-in for the signed-in customer. */
    val customer = Customer(
        id = "c-1",
        name = "Hung",
        phone = "+84900000001",
        email = "hung@example.com",
        channels = setOf(ChannelType.PUSH, ChannelType.SMS, ChannelType.EMAIL)
    )

    /** Which region each sample restaurant belongs to (a real backend would send this with the restaurant). */
    val regionOfRestaurant: Map<String, String> = mapOf(
        InMemoryMenuRepository.VN_RESTAURANT to VietnamRegionFactory.CODE,
        InMemoryMenuRepository.US_RESTAURANT to UsRegionFactory.CODE
    )
}
