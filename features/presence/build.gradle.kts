plugins { alias(libs.plugins.android.library); alias(libs.plugins.compose.compiler) }
android {
    namespace = "io.github.escossio.andy.features.presence"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 28 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sceneview) {
        exclude(group = "com.github.kittinunf.fuel")
    }
    testImplementation(libs.junit)
}
