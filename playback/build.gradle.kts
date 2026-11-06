// SPDX-License-Identifier: GPL-3.0-or-later
plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
android {
    namespace = "com.walkmanstudio.nativeplayer.playback"
    compileSdk = 36
    ndkVersion = "30.0.16248370"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
dependencies {
    implementation(project(":library"))
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core)
    api(libs.media3.exoplayer)
    api(libs.media3.session)
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
}
val hostNative by tasks.registering(Exec::class) {
    group="verification"
    description="Compile the same C++ FIR/JNI for host tests; run quantitative benchmark. Requires Linux g++."
    workingDir(rootProject.projectDir)
    commandLine("python3",rootProject.file("qa/native-host.py").absolutePath)
    inputs.files(fileTree("src/main/cpp"),rootProject.file("qa/native-host.py"))
    outputs.files(layout.buildDirectory.file("native-host/libwalkman_dsd.so"),rootProject.file("qa/dsd-host-benchmark.json"))
}
tasks.withType<Test>().configureEach {
    dependsOn(hostNative)
    systemProperty("java.library.path",layout.buildDirectory.dir("native-host").get().asFile.absolutePath)
    useJUnit(); maxHeapSize = "1536m"
    System.getProperty("walkman.test.userHome")?.let { systemProperty("user.home", it) }
    System.getProperties().filter { it.key.toString().startsWith("robolectric.") }.forEach { (key,value) -> systemProperty(key.toString(),value) }
    listOf("http.proxyHost","http.proxyPort","https.proxyHost","https.proxyPort","javax.net.ssl.trustStore").forEach { key -> System.getProperty(key)?.let { systemProperty(key,it) } }
}
