package com.nemis.foodhub.data

import com.nemis.foodhub.domain.cart.CartDraftRepository
import com.nemis.foodhub.domain.cart.CartSnapshot
import com.nemis.foodhub.domain.cart.CartSnapshotCodec
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.text.Charsets.UTF_8

/**
 * Keeps one draft file per customer in [directory] (on Android: the app's private files dir), so a cart survives
 * the process being killed. The text comes from [codec] (the Cart itself); this class never looks inside it.
 * A damaged file counts as "no draft" rather than crashing the app on start.
 */
class FileCartDraftRepository(private val directory: File, private val codec: CartSnapshotCodec) : CartDraftRepository {

    init {
        directory.mkdirs()
    }

    @Synchronized
    override fun save(customerId: String, snapshot: CartSnapshot) {
        val target = fileOf(customerId)
        val temp = File(directory, target.name + ".tmp")
        temp.writeText(codec.encode(snapshot))
        // Write-then-rename: a crash while writing leaves the previous draft intact.
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Synchronized
    override fun load(customerId: String): CartSnapshot? {
        val file = fileOf(customerId)
        if (!file.isFile) return null
        return try {
            codec.decode(file.readText())
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    @Synchronized
    override fun clear(customerId: String) {
        fileOf(customerId).delete()
    }

    private fun fileOf(customerId: String) = File(directory, URLEncoder.encode(customerId, UTF_8) + ".cart")
}
