plugins { alias(libs.plugins.android.library) }
android {
    namespace = "io.github.escossio.andy.integrations.googleidentity"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.id)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
