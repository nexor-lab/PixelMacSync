import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Fixed build timestamp, stamped into debug builds only (watermark / diagnostics).
val debugBuildStamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())

android {
    namespace = "it.luigi.macsync"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "it.luigi.macsync"
        // Android 16 (36) is the primary target; Android 15 (35) is the
        // minimum supported. All APIs used are available on API 35.
        minSdk = 35
        targetSdk = 36
        versionCode = 4
        versionName = "2.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Clearly separate the test build from the release app: it installs
            // side by side (separate package) and is visibly marked as DEBUG.
            // This is the ONLY variant with on-device diagnostics (see Diagnostics
            // / DebugJournal); beta and release must never store that content.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "BUILD_TIMESTAMP", "\"$debugBuildStamp\"")
            buildConfigField("boolean", "IS_BETA", "false")
        }
        release {
            isMinifyEnabled = true // Attiva R8 per offuscare e rimuovere il codice morto
            isShrinkResources = true // Rimuove file grafici e XML inutilizzati
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "BUILD_TIMESTAMP", "\"\"")
            buildConfigField("boolean", "IS_BETA", "false")
        }
        // Beta = the debug feature set, renamed + Beta watermark, published to
        // GitHub. It inherits debug (fast, debuggable, debug-signed for easy
        // install) but uses the RELEASE package (no ".debug" suffix). It has NO
        // on-device diagnostics: the only diagnostics sink lives in /src/debug,
        // which is not part of this variant.
        create("beta") {
            // Minified + resource-shrunk like release (much smaller APK), but
            // debug-signed so it installs easily. Beta branding via src/beta,
            // IS_BETA=true. No debug diagnostics (src/debug is debug-only).
            initWith(getByName("release"))
            applicationIdSuffix = ""
            versionNameSuffix = "-beta"
            buildConfigField("boolean", "IS_BETA", "true")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Navigazione e animazioni native
    implementation("androidx.navigation:navigation-compose:2.8.0")
    // Caricamento asincrono delle icone (Coil)
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.compose.material:material-icons-extended")
    // Libreria ufficiale Google per le forme avanzate MD3
    implementation("androidx.graphics:graphics-shapes:1.0.1")
    // Shizuku: privileged shell (hotspot, ecc.) senza root
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}