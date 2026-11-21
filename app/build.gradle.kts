plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.leeuw008.tijdbeeld"
    compileSdk = 35

    defaultConfig {
        applicationId = "nl.leeuw008.tijdbeeld.diagnose32stable"
        minSdk = 24
        targetSdk = 32
        versionCode = 2
        versionName = "0.1.3"
        // ARCore API key is injected from the GitHub Actions secret at build time.
        manifestPlaceholders["appLabel"] = "AR-TijdBeeld"
        manifestPlaceholders["arcoreApiKey"] = System.getenv("ARCORE_API_KEY") ?: ""
    }

    signingConfigs {
        create("persistentDiagnostic") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            // Reuse the exact #139 signing key so this APK updates #139 in place.
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
    // Retain the auth library as a safe dependency; Geospatial will use the explicit API key.
    implementation("com.google.android.gms:play-services-auth:21.6.0")
}
