package com.nemis.foodhub

import com.nemis.foodhub.data.FileCartDraftRepository
import com.nemis.foodhub.data.InMemoryCartDraftRepository
import com.nemis.foodhub.data.InMemoryMenuRepository
import com.nemis.foodhub.domain.ValidationException
import com.nemis.foodhub.domain.cart.Cart
import com.nemis.foodhub.domain.cart.CartEditor
import com.nemis.foodhub.domain.cart.CartHistory
import com.nemis.foodhub.domain.cart.CartSnapshot
import com.nemis.foodhub.domain.model.Currency
import com.nemis.foodhub.domain.model.MenuItem
import com.nemis.foodhub.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/** C6: Memento pattern. */
class CartMementoTest {

    private val clock = MutableClock()
    private val menu = InMemoryMenuRepository()
    private val pho = menu.findItem("pho-bo")!!
    private val coffee = menu.findItem("ca-phe-sua")!!
    private val burger = menu.findItem("cheeseburger")!!

    private val drafts = InMemoryCartDraftRepository()
    private fun editor(capacity: Int = 10, cart: Cart = Cart(clock)) = CartEditor(cart, CartHistory(capacity), drafts, "c-1")
    private fun quantities(cart: Cart) = cart.items.associate { it.item.id to it.quantity }

    // ---- originator / memento ----

    @Test
    fun restoreBringsBackExactlyTheSavedState() {
        val cart = Cart(clock)
        cart.add(pho, 2)
        val snapshot = cart.createSnapshot()
        cart.add(coffee); cart.setQuantity("pho-bo", 5)

        cart.restore(snapshot)

        assertEquals(mapOf("pho-bo" to 2), quantities(cart))
    }

    @Test
    fun aSnapshotIsNotAffectedByLaterChangesToTheCart() {
        val cart = Cart(clock)
        cart.add(pho)
        val snapshot = cart.createSnapshot()
        cart.clear()
        cart.restore(snapshot)
        cart.add(coffee)
        cart.restore(snapshot)
        assertEquals(mapOf("pho-bo" to 1), quantities(cart))
    }

    @Test
    fun theCaretakerSeesOnlyATimestampAndCannotForgeSnapshots() {
        val cart = Cart(clock)
        val real = cart.createSnapshot()
        assertEquals(clock.instant(), real.takenAt)
        assertTrue(CartSnapshot::class.java.declaredMethods.map { it.name }.toSet() == setOf("getTakenAt"))

        val forged = object : CartSnapshot { override val takenAt: Instant = Instant.EPOCH }
        assertThrows(IllegalArgumentException::class.java) { cart.restore(forged) }
    }

    @Test
    fun aSnapshotCanBeRestoredIntoAnotherCartInstanceLikeAfterARestart() {
        val first = Cart(clock).also { it.add(pho, 3) }
        val snapshot = first.createSnapshot()
        val second = Cart(clock)
        second.restore(snapshot)
        assertEquals(mapOf("pho-bo" to 3), quantities(second))
    }

    // ---- cart rules ----

    @Test
    fun cartRejectsItemsFromASecondRestaurantAndBadQuantities() {
        val cart = Cart(clock).also { it.add(pho) }
        assertThrows(ValidationException::class.java) { cart.add(burger) }
        assertThrows(ValidationException::class.java) { cart.add(pho, 0) }
        assertThrows(ValidationException::class.java) { cart.remove("nope") }
        assertThrows(ValidationException::class.java) { cart.setQuantity("nope", 1) }
        assertThrows(ValidationException::class.java) { cart.setQuantity("pho-bo", -1) }
        cart.setQuantity("pho-bo", 0)
        assertTrue(cart.isEmpty)
    }

    @Test
    fun addingTheSameItemIncreasesItsQuantity() {
        val cart = Cart(clock)
        cart.add(pho); cart.add(pho, 2)
        assertEquals(mapOf("pho-bo" to 3), quantities(cart))
    }

    // ---- caretaker: bounded history ----

    @Test
    fun undoWalksBackThroughTheBackupsInReverseOrder() {
        val e = editor()
        e.add(pho); e.add(coffee); e.setQuantity("pho-bo", 4)

        assertTrue(e.undo()); assertEquals(mapOf("pho-bo" to 1, "ca-phe-sua" to 1), quantities(e.cart))
        assertTrue(e.undo()); assertEquals(mapOf("pho-bo" to 1), quantities(e.cart))
        assertTrue(e.undo()); assertTrue(e.cart.isEmpty)
        assertFalse(e.undo())
        assertFalse(e.canUndo)
    }

    @Test
    fun onlyTheNewestNBackupsAreKept() {
        val e = editor(capacity = 3)
        repeat(5) { e.add(pho) } // quantity 5, five backups made, only 3 kept

        var undone = 0
        while (e.undo()) undone++

        assertEquals(3, undone)
        assertEquals(mapOf("pho-bo" to 2), quantities(e.cart))
    }

    @Test
    fun historyCapacityMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { CartHistory(0) }
    }

    @Test
    fun aFailedChangeLeavesHistoryAndDraftUntouched() {
        val e = editor()
        e.add(pho)
        val draftBefore = drafts.load("c-1")

        assertThrows(ValidationException::class.java) { e.add(burger) }

        assertEquals(draftBefore, drafts.load("c-1"))
        assertTrue(e.undo())
        assertFalse("only the one successful change was recorded", e.undo())
    }

    // ---- drafts / crash recovery ----

    @Test
    fun everyChangeIsSavedAsADraftThatSurvivesTheEditor() {
        editor().also { it.add(pho, 2); it.add(coffee) }

        val afterCrash = editor()
        assertTrue(afterCrash.cart.isEmpty)
        assertTrue(afterCrash.restoreDraft())

        assertEquals(mapOf("pho-bo" to 2, "ca-phe-sua" to 1), quantities(afterCrash.cart))
        assertNotNull(drafts.load("c-1"))
    }

    @Test
    fun restoringADraftCanBeUndone() {
        editor().also { it.add(pho, 2) }
        val e = editor()
        e.add(coffee) // overwrites the draft with a cart that only has coffee
        // simulate a stale draft: a fresh editor restores the latest draft (coffee), then undoes it
        val fresh = editor()
        assertTrue(fresh.restoreDraft())
        assertTrue(fresh.undo())
        assertTrue(fresh.cart.isEmpty)
    }

    @Test
    fun anEmptiedCartLeavesNoDraftBehind() {
        val e = editor()
        e.add(pho)
        e.remove("pho-bo")
        assertNull("an empty cart is not worth restoring", drafts.load("c-1"))

        e.undo()
        assertNotNull(drafts.load("c-1"))
    }

    @Test
    fun noDraftMeansNothingToRestore() {
        drafts.clear("c-1")
        assertFalse(editor().restoreDraft())
        assertNull(drafts.load("c-1"))
    }

    @Test
    fun draftsAreKeptPerCustomer() {
        CartEditor(Cart(clock), CartHistory(5), drafts, "alice").add(pho)
        assertNull(drafts.load("bob"))
        assertNotNull(drafts.load("alice"))
    }

    // ---- the draft survives the process being killed ----

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fileEditor(dir: java.io.File) =
        CartEditor(Cart(clock), CartHistory(5), FileCartDraftRepository(dir, Cart.SnapshotCodec), "c-1")

    @Test
    fun aDraftWrittenToDiskSurvivesTheProcess() {
        val dir = tmp.newFolder("drafts")
        fileEditor(dir).also { it.add(pho, 2); it.add(coffee) }

        val restarted = fileEditor(dir) // new repository, new cart: nothing shared in memory
        assertTrue(restarted.hasDraft)
        assertTrue(restarted.restoreDraft())

        assertEquals(mapOf("pho-bo" to 2, "ca-phe-sua" to 1), quantities(restarted.cart))
        assertEquals(pho, restarted.cart.items.first().item)
    }

    @Test
    fun theEncodingRoundTripsAnyTextAndOnlyTheCartCanProduceOrReadIt() {
        val tricky = MenuItem("x,1", "r-vn-pho", "Phở \"đặc biệt\", size L\nmới", Money.of(65_000, Currency.VND))
        val cart = Cart(clock).also { it.add(tricky, 2); it.add(pho) }
        val text = Cart.SnapshotCodec.encode(cart.createSnapshot())

        val decoded = Cart.SnapshotCodec.decode(text)
        val copy = Cart(clock).also { it.restore(decoded) }

        assertEquals(cart.items, copy.items)
        assertEquals(clock.instant(), decoded.takenAt)
        assertThrows(IllegalArgumentException::class.java) { Cart.SnapshotCodec.decode("not a cart") }
        val forged = object : CartSnapshot { override val takenAt: Instant = Instant.EPOCH }
        assertThrows(IllegalArgumentException::class.java) { Cart.SnapshotCodec.encode(forged) }
    }

    @Test
    fun aDamagedDraftFileCountsAsNoDraft() {
        val dir = tmp.newFolder("drafts")
        fileEditor(dir).add(pho)
        dir.listFiles()!!.single().writeText("garbage")

        assertFalse(fileEditor(dir).hasDraft)
        assertFalse(fileEditor(dir).restoreDraft())
    }

    // ---- after checkout; change listeners ----

    @Test
    fun completingCheckoutClearsCartHistoryAndDraft() {
        val e = editor()
        e.add(pho, 2)

        e.completeCheckout()

        assertTrue(e.cart.isEmpty)
        assertFalse(e.canUndo)
        assertFalse(e.hasDraft)
        assertNull(drafts.load("c-1"))
    }

    @Test
    fun listenersHearEverySuccessfulChangeUntilTheyCancel() {
        val e = editor()
        var changes = 0
        val subscription = e.observe { changes++ }

        e.add(pho); e.undo(); e.restoreDraft(); e.completeCheckout()
        assertThrows(ValidationException::class.java) { e.remove("nope") }
        assertEquals(3, changes) // add, undo, completeCheckout (restoreDraft found no draft)

        subscription.cancel()
        e.add(pho)
        assertEquals(3, changes)
    }
}
