// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.walkmanstudio.nativeplayer"
    compileSdk = 36
    ndkVersion = "30.0.16248370"
    buildToolsVersion = "35.0.0"
    defaultConfig { applicationId = "com.walkmanstudio.nativeplayer"; minSdk = 26; targetSdk = 35; ndk { abiFilters += listOf("arm64-v8a", "x86_64") }; versionCode = 107; versionName = "1.3.1-native-usb" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildTypes {
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
        create("review") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
}
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
dependencies {
    implementation(project(":library"))
    implementation(project(":playback"))
    implementation(libs.androidx.core)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.paging.compose)
    implementation(libs.koin.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.androidx.test.core)
    implementation(libs.work.runtime)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
tasks.withType<Test>().configureEach {
    useJUnit()
    maxHeapSize = "1536m"
    System.getProperty("walkman.test.userHome")?.let { systemProperty("user.home", it) }
    System.getProperties().filter { it.key.toString().startsWith("robolectric.") }.forEach { (key, value) -> systemProperty(key.toString(), value) }
    listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "javax.net.ssl.trustStore").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
