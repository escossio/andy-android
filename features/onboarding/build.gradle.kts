plugins { alias(libs.plugins.android.library); alias(libs.plugins.compose.compiler) }
android {
    namespace = "io.github.escossio.andy.features.onboarding"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 28 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    api(project(":core:human-identity"))
    implementation(project(":sdk:client-api"))
    implementation(project(":integrations:google-identity"))
    implementation(project(":integrations:google-authorization"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
