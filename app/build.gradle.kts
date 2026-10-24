
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24"
    id("com.google.gms.google-services")
}

// ---------------------------------------------------------------------------
// Secrets are read from local.properties or environment variables.
// They are NEVER hardcoded here. Debug builds work without any of them;
// only assembleRelease needs the release signing values.
// See local.properties.example for the expected keys.
// ---------------------------------------------------------------------------
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(name: String): String? =
    (localProperties.getProperty(name)
        ?: System.getenv(name.replace('.', '_').uppercase()))
        ?.takeIf { it.isNotBlank() }

val googleWebClientId = secret("google.web.client.id") ?: ""

val releaseStoreFilePath = secret("release.storeFile")
val releaseStorePassword = secret("release.storePassword")
val releaseKeyAlias = secret("release.keyAlias")
val releaseKeyPassword = secret("release.keyPassword")

android {
    namespace = "com.dev.nudge"
    compileSdk {
        version = release(36)
    }

    signingConfigs {
        if (releaseStoreFilePath != null &&
            releaseStorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null
        ) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.dev.nudge"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Unsigned (or debug-signed) release builds are fine when no
            // keystore is configured; assembleRelease will fail at signing time.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // java.time (LocalDate streak/stats logic) on minSdk 24 — spec §7
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation(platform("com.google.firebase:firebase-bom:34.11.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth:22.3.0")
    // 21.6.0: AuthorizationClient (Drive backup, spec §9) + GoogleSignIn both present
    implementation(libs.play.services.auth)
    implementation("com.google.firebase:firebase-auth-ktx:23.0.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.google.firebase:firebase-firestore-ktx:25.0.0")

    // 🗄 Room — focus sessions + segments (append-heavy, query-heavy)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // java.time on minSdk 24 (StreakCalculator/StatsCalculator use LocalDate)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
