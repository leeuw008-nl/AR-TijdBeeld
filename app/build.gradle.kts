plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.leeuw008.tijdbeeld"
    compileSdk = 35

    defaultConfig {
        // Separate package identity: install alongside #139; never update or replace it.
        applicationId = "nl.leeuw008.tijdbeeld.authfix"
        minSdk = 24
        targetSdk = 32
        versionCode = 2
        versionName = "0.1.3"
        manifestPlaceholders["appLabel"] = "AR-TijdBeeld"
        // Inject the ARCore API key from the GitHub Actions secret; never store it in source.
        manifestPlaceholders["arcoreApiKey"] = System.getenv("ARCORE_API_KEY") ?: ""
    }

    signingConfigs {
        create("persistentDiagnostic") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            // Match the known-good #139 APK signature schemes for installer compatibility.
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        getByName("debug") {
            // Reuse the verified #139 signing certificate; the distinct package installs side-by-side.
            signingConfig = signingConfigs.getByName("persistentDiagnostic")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.ar:core:1.56.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Keep Google auth available; the Geospatial API will use the explicit API key.
    implementation("com.google.android.gms:play-services-auth:21.6.0")
}
