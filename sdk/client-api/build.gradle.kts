plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:human-identity"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
