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
        versionCode = 4
        versionName = "0.1.4"
    }

    signingConfigs {
        create("originalRelease") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
                ?: error("ANDROID_KEYSTORE_PATH is missing")
            storeFile = file(keystorePath)
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                ?: error("ANDROID_KEYSTORE_PASSWORD is missing")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                ?: error("ANDROID_KEY_ALIAS is missing")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                ?: error("ANDROID_KEY_PASSWORD is missing")
        }
    }

    buildTypes {
        getByName("debug") {
            manifestPlaceholders["appLabel"] = "AR-TijdBeeld"
            // Sign every diagnostic APK with the original keystore so it can update build 231.
            signingConfig = signingConfigs.getByName("originalRelease")
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
