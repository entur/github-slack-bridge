plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    application
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20"
    alias(libs.plugins.shadow)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation.jvm)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()

    // Enhanced test logging for console output - show only failed tests
    testLogging {
        events("failed")
        showExceptions = true
        showCauses = true
        showStackTraces = false // Reduce stack trace output
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT // Use SHORT format instead of FULL
        showStandardStreams = false // Only show standard streams for failures
    }
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(23)
    }
}

application {
    mainClass = "no.entur.github_slack_bridge.AppKt"
}
