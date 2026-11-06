// SPDX-License-Identifier: GPL-3.0-or-later
plugins { alias(libs.plugins.kotlin.jvm); `java-test-fixtures` }
kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
tasks.test { useJUnit() }
