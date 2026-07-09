plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Keys: env (CI) -> local.properties (dev) -> empty. Anon key is public, but injecting keeps it out of git.
fun secret(key: String): String {
    System.getenv(key)?.let { return it }
    val lp = rootProject.file("local.properties")
    if (lp.exists()) {
        val p = java.util.Properties().apply { lp.inputStream().use { load(it) } }
        p.getProperty(key)?.let { return it }
    }
    return ""
}

android {
    namespace = "com.mirko.glasstodo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mirko.glasstodo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secret("SUPABASE_ANON_KEY")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.tooling.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.haze)
    implementation(libs.haze.materials)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.auth)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.work.runtime)
    implementation(libs.core.ktx)
}
