// Pure-JVM module (no Android plugin): domain, data, presentation (ViewModels) and the composition root.
// The domain must stay free of android.* so its tests run on the JVM without an emulator: ./gradlew :core:test
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    jacoco
}

dependencies {
    implementation(libs.kotlinx.coroutines.core) // StateFlow, used by presentation only
    testImplementation(libs.junit)
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    // Console demo of the whole flow (no Android needed): ./gradlew :core:run
    mainClass.set("com.nemis.foodhub.app.MainKt")
}

tasks.test {
    useJUnit()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            element = "PACKAGE"
            includes = listOf("com.nemis.foodhub.domain*")
            limit {
                counter = "LINE"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}
