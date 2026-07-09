import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Keys: env (CI) -> local.properties (dev) -> empty. Anon key is public, but injecting keeps it out of git.
fun secret(key: String): String {
    val env = System.getenv(key)
    if (!env.isNullOrEmpty()) return env
    val lp = rootProject.file("local.properties")
    if (lp.exists()) {
        val props = Properties()
        lp.inputStream().use { props.load(it) }
        return props.getProperty(key) ?: ""
    }
    return ""
}

// Stable release signing. Without this the APK is signed with the throwaway debug keystore that AGP
// generates on whatever machine happens to build it — a fresh one per CI runner — so every release
// carries a different certificate and Android refuses to install it over the previous one
// (INSTALL_FAILED_UPDATE_INCOMPATIBLE). That is why the in-app updater never worked.
// Keystore lives OUTSIDE the repo; CI materialises it from a base64 secret.
val signingKeystorePath: String = secret("SIGNING_KEYSTORE_PATH")
val signingAvailable: Boolean = signingKeystorePath.isNotBlank() && file(signingKeystorePath).exists()

android {
    namespace = "com.mirko.glasstodo"
    compileSdk = 36

    signingConfigs {
        if (signingAvailable) {
            create("release") {
                storeFile = file(signingKeystorePath)
                storePassword = secret("SIGNING_STORE_PASSWORD")
                keyAlias = secret("SIGNING_KEY_ALIAS")
                keyPassword = secret("SIGNING_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.mirko.glasstodo"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.1.1"
        buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secret("SUPABASE_ANON_KEY")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true   // Robolectric/Roborazzi need merged resources
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // null when no keystore is configured (e.g. a contributor's clean checkout): the build
            // still succeeds, it just produces an unsigned APK instead of failing.
            signingConfig = signingConfigs.findByName("release")
        }
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

    // --- v2: offline-first + reactive + widget ---
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.supabase.realtime)

    // --- tests (JVM, no emulator) ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation(libs.room.testing)
    testImplementation(libs.glance.testing)
    testImplementation(libs.glance.appwidget.testing)
}
