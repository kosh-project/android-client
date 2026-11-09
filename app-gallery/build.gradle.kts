plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)

    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.serializaton)
    alias(libs.plugins.hilt)
}

android {
    namespace = "project.kosh.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "project.kosh.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("sharedDebug") {
            storeFile = file("kosh-dev.keystore")
            storePassword = System.getenv("DEV_KEY_PASS") ?: "android"
            keyAlias = "koshdebug"
            keyPassword = System.getenv("DEV_KEY_PASS") ?: "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sharedDebug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(project(":core:crypto"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:auth"))
    implementation(project(":core:database"))

    implementation(project(":feature:auth"))
    implementation(project(":feature:gallery"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Networking
    implementation(libs.squareup.okhttp)
    implementation(libs.squareup.okhttp.logging.interceptor)

    // SerDe
    implementation(libs.kotlinx.serialization.json)

    // Dependency Injection
    implementation(libs.dagger.hilt.android)
    ksp(libs.dagger.hilt.compiler)
    ksp(libs.kotlin.metadata.jvm)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    // VM Compose
    implementation(libs.lifecycle.viewmodel.compose)

    // Keystore
    implementation(libs.androidx.security.crypto)

    // Crypto
    implementation(libs.argon2kt)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}