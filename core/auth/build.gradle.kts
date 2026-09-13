plugins {
    id("com.android.library")
    alias (libs.plugins.ksp)
    alias (libs.plugins.hilt)
}

android {
    namespace = "project.kosh.core.auth"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    implementation(libs.dagger.hilt.android)
    ksp(libs.dagger.hilt.compiler)
    ksp(libs.kotlin.metadata.jvm)

    implementation(libs.androidx.security.crypto)
    implementation(project(":core:model"))
    implementation(project(":core:network"))
}