package com.nemis.foodhub

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Enforces the dependency rule Presentation -> Domain <- Data by scanning the sources,
 * and guards against the anti-patterns listed in the assignment.
 */
class ArchitectureTest {

    private val root: File? = listOf("core/src/main/kotlin", "src/main/kotlin")
        .map { File(System.getProperty("user.dir"), it) }
        .firstOrNull { it.isDirectory }

    private fun sources(layer: String): List<File> {
        assumeTrue("sources not found from ${System.getProperty("user.dir")}", root != null)
        return File(root, "com/nemis/foodhub/$layer").walkTopDown().filter { it.extension == "kt" }.toList()
    }

    private fun imports(file: File) = file.readLines().filter { it.startsWith("import ") }

    private fun assertNoImport(layer: String, forbiddenPrefix: String) {
        val files = sources(layer)
        assertTrue("no sources found for $layer", files.isNotEmpty())
        val offenders = files.filter { f -> imports(f).any { it.removePrefix("import ").startsWith(forbiddenPrefix) } }
        assertTrue("$layer must not import $forbiddenPrefix: ${offenders.map { it.name }}", offenders.isEmpty())
    }

    @Test
    fun domainIsPureJvm() {
        assertNoImport("domain", "android.")
        assertNoImport("domain", "androidx.")
        assertNoImport("domain", "kotlinx.coroutines")
    }

    @Test
    fun domainDoesNotDependOnOuterLayers() {
        assertNoImport("domain", "com.nemis.foodhub.data")
        assertNoImport("domain", "com.nemis.foodhub.presentation")
        assertNoImport("domain", "com.nemis.foodhub.app")
    }

    @Test
    fun presentationDoesNotKnowTheDataLayerOrTheAndroidFramework() {
        assertNoImport("presentation", "com.nemis.foodhub.data")
        assertNoImport("presentation", "com.nemis.foodhub.app")
        assertNoImport("presentation", "android.")
    }

    @Test
    fun dataLayerDoesNotKnowPresentation() {
        assertNoImport("data", "com.nemis.foodhub.presentation")
        assertNoImport("data", "com.nemis.foodhub.app")
    }

    @Test
    fun noSingletonsInTheDomainExceptTheStatelessOrderStates() {
        val offenders = sources("domain")
            .filter { it.name != "OrderState.kt" }
            .filter { f -> f.readLines().any { it.trimStart().startsWith("object ") && !it.contains("object :") } }
        assertTrue("object declarations found in: ${offenders.map { it.name }}", offenders.isEmpty())
    }

    @Test
    fun noBranchingOnOrderStatusAnywhereInTheDomain() {
        val regex = Regex("""when\s*\(\s*(\w+\.)*(status|state)\b""", RegexOption.IGNORE_CASE)
        val offenders = sources("domain").filter { f -> f.readLines().any { regex.containsMatchIn(it) } }
        assertTrue("status switching in: ${offenders.map { it.name }}", offenders.isEmpty())
        // Any comparison with an OrderStatus constant, or of a status-typed property (status/current/previous).
        val comparisons = listOf(
            Regex("""(==|!=)\s*OrderStatus\.[A-Z_]+"""),
            Regex("""OrderStatus\.[A-Z_]+\s*(==|!=)"""),
            Regex("""\b(status|current|previous)\s*(==|!=)\s*\w"""),
            Regex("""\bin\s+(setOf|listOf)\(\s*OrderStatus\.""")
        )
        val ifOffenders = sources("domain").filter { f ->
            f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                .any { line -> comparisons.any { it.containsMatchIn(line) } }
        }
        assertTrue("status comparison in: ${ifOffenders.map { it.name }}", ifOffenders.isEmpty())
    }

    // ---- Android views (module :app): render state and forward events to ViewModels, nothing else ----

    private val appRoot: File? = listOf("app/src/main/java", "../app/src/main/java")
        .map { File(System.getProperty("user.dir"), it) }
        .firstOrNull { it.isDirectory }

    private fun androidFile(path: String): File {
        assumeTrue("app sources not found from ${System.getProperty("user.dir")}", appRoot != null)
        return File(appRoot, "com/nemis/foodhub/$path")
    }

    private fun screens(): List<File> = listOf("ui/ordering", "ui/tracking")
        .flatMap { androidFile(it).walkTopDown().filter { f -> f.extension == "kt" }.toList() }

    @Test
    fun screensTalkOnlyToViewModels() {
        val files = screens()
        assertTrue("no screens found", files.isNotEmpty())
        val forbidden = listOf(
            "com.nemis.foodhub.app.", "com.nemis.foodhub.data.", "com.nemis.foodhub.domain.repository",
            "com.nemis.foodhub.domain.usecase", "com.nemis.foodhub.domain.checkout", "com.nemis.foodhub.domain.facade",
            "com.nemis.foodhub.domain.notification", "com.nemis.foodhub.domain.pricing",
            "com.nemis.foodhub.domain.payment.Simulated", "com.nemis.foodhub.domain.DomainException"
        )
        val offenders = files.filter { f -> imports(f).any { line -> forbidden.any { line.removePrefix("import ").startsWith(it) } } }
        assertTrue("screens import services, data or the composition root: ${offenders.map { it.name }}", offenders.isEmpty())
        val catching = files.filter { it.readText().contains("catch (") }
        assertTrue("screens must not handle domain errors themselves: ${catching.map { it.name }}", catching.isEmpty())
    }

    @Test
    fun theActivityUsesTheContainerOnlyToCreateViewModels() {
        val text = androidFile("MainActivity.kt").readText()
        val shortcuts = listOf("container.orders", "container.lifecycle", "container.promoAnnouncer", "container.sentMessages", "container.facade", "container.menu")
        assertTrue("MainActivity bypasses the ViewModels: ${shortcuts.filter { it in text }}", shortcuts.none { it in text })
    }

    @Test
    fun businessRulesAreNotInThePresentationLayer() {
        // ViewModels must not compute fees, taxes or know the cancellation percentage themselves.
        val offenders = sources("presentation").filter { f ->
            f.readText().let { it.contains("CANCELLATION_FEE") || it.contains("percent(") || it.contains("REFUND_WINDOW") }
        }
        assertTrue(offenders.map { it.name }.toString(), offenders.isEmpty())
    }
}
