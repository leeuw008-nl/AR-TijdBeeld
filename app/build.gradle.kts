plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val fixedKeystoreFile = file("tijdbeeld-release.jks")
val hasFixedKeystore = fixedKeystoreFile.exists()

android {
    namespace = "nl.leeuw008.tijdbeeld"
    compileSdk = 35

    defaultConfig {
        applicationId = "nl.leeuw008.tijdbeeld"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0"
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
            manifestPlaceholders["appLabel"] = "AR-TijdBeeld"
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
    // Required by ARCore Geospatial keyless authorization.
    implementation("com.google.android.gms:play-services-auth:21.6.0")
}
