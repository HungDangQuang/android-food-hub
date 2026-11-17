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

    @Test
    fun businessRulesAreNotInThePresentationLayer() {
        // ViewModels must not compute fees, taxes or know the cancellation percentage themselves.
        val offenders = sources("presentation").filter { f ->
            f.readText().let { it.contains("CANCELLATION_FEE") || it.contains("percent(") || it.contains("REFUND_WINDOW") }
        }
        assertTrue(offenders.map { it.name }.toString(), offenders.isEmpty())
    }
}
