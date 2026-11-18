plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val fixedKeystoreFile = file("tijdbeeld-release.jks")
// Diagnostic only: use the standard Android debug certificate to isolate signing compatibility.
val hasFixedKeystore = false

android {
    namespace = "nl.leeuw008.tijdbeeld"
    compileSdk = 35

    defaultConfig {
        // Separate package so this diagnostic can be installed alongside production.
        applicationId = "nl.leeuw008.tijdbeeld.diagnose32"
        minSdk = 24
        targetSdk = 32
        versionCode = 2
        versionName = "0.1.0-diagnose32"
    }

    signingConfigs {
        if (hasFixedKeystore) {
            create("fixedDebug") {
                storeFile = fixedKeystoreFile
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "tijdbeeld"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: ""
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            manifestPlaceholders["appLabel"] = "AR-TijdBeeld diagnose SDK32"
            if (hasFixedKeystore) {
                signingConfig = signingConfigs.getByName("fixedDebug")
            }
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
    implementation("com.google.android.gms:play-services-auth:21.6.0")
}
