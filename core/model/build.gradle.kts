import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.kotlin

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinx.serializaton)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain (17)
}
