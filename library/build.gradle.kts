// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.walkmanstudio.nativeplayer.library"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    api(project(":core"))
    implementation(libs.androidx.core)
    implementation(libs.coroutines.android)
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.media3.extractor)
    api(libs.paging.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
}
tasks.withType<Test>().configureEach {
    useJUnit()
    maxHeapSize = "2g"
    System.getProperty("walkman.test.userHome")?.let { systemProperty("user.home", it) }
    systemProperty("walkman.benchmark.output", rootProject.file("qa/benchmark-room-100k.json").absolutePath)
    System.getProperties().filter { it.key.toString().startsWith("robolectric.") }.forEach { (key, value) -> systemProperty(key.toString(), value) }
    listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "javax.net.ssl.trustStore").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
