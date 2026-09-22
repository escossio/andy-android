plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val attentionRouterBaseUrl = providers.gradleProperty("attentionRouterBaseUrl").orElse("").get()
val googleWebClientId = providers.gradleProperty("googleWebClientId").orElse("").get()

android {
    namespace = "io.github.escossio.andy"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.escossio.andy"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-human-identity"
        buildConfigField("String", "ATTENTION_ROUTER_BASE_URL", "\"$attentionRouterBaseUrl\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:device-identity"))
    implementation(project(":data:device-identity"))
    implementation(project(":data:client-session"))
    implementation(project(":data:location"))
    implementation(project(":features:onboarding"))
    implementation(project(":features:approvals"))
    implementation(project(":features:command"))
    implementation(project(":sdk:client-api"))
    implementation(project(":integrations:google-identity"))
    implementation(project(":integrations:google-authorization"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)

    testImplementation(libs.junit)
}
