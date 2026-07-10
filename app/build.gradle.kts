import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
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
        versionCode = 12
        versionName = "1.4.0"
        buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secret("SUPABASE_ANON_KEY")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        // MigrationTestHelper loads the exported schema JSONs from the assets of the context it is
        // given. Under Robolectric that context reads `android_merged_assets`, which AGP writes into
        // test_config.properties as mergeDebugAssets — the MAIN/BUILD-TYPE assets. There is no
        // mergeDebugUnitTestAssets task, so a `test { assets }` entry is silently ignored.
        // Hanging the schemas off the `debug` source set feeds mergeDebugAssets (which is what
        // testDebugUnitTest sees) while keeping them out of the release APK.
        getByName("debug") {
            assets.srcDir("$projectDir/schemas")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true   // Robolectric/Roborazzi need merged resources
            all {
                // Roborazzi renders through Robolectric's native graphics; without this it captures blanks.
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            }
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

// Room exports each schema version to app/schemas/, versioned in git. Without this there is no JSON
// for v1 and MigrationTestHelper cannot validate the 1->2 migration.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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

    // Haze (the liquid-glass blur) is gone with the "Glass Todo" identity: the redesign is flat ink,
    // one accent, and type. A blur nobody sees is 300 kB of APK and a frame of GPU per scroll.

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

    // Screenshot evidence: renders the real composables (and the widget's RemoteViews) to PNG on the JVM.
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
