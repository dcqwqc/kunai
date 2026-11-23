plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.kitsunekode.kunai.android"
    compileSdk = 37
    // A caller may select an existing debug signing identity to upgrade
    // installed development APKs without wiping their private device data.
    // Never create a new keystore as a silent fallback for that operation.
    signingConfigs {
        getByName("debug") {
            // Prefer the established XDG debug identity if present. It signs
            // existing owner installs on Mirai; ordinary contributors without
            // that file still use their normal Android debug keystore.
            val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                ?: System.getProperty("user.home") + "/.config"
            val existing = providers.gradleProperty("kunaiDebugKeystore").orNull
                ?: file("$xdg/.android/debug.keystore")
                    .takeIf { it.isFile }?.absolutePath
            existing?.let { selected ->
                val key = file(selected)
                require(key.isFile) { "Kunai debug signing keystore does not exist" }
                storeFile = key
                storePassword = "android"
                keyAlias = "AndroidDebugKey"
                keyPassword = "android"
            }
        }
    }
    defaultConfig {
        applicationId = "dev.kitsunekode.kunai"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "0.1.14"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources { excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
    }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation("androidx.compose.animation:animation-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
