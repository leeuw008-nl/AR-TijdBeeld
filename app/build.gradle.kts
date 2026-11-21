plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.leeuw008.tijdbeeld"
    compileSdk = 35

    defaultConfig {
        // Separate package: #139 remains installed and untouched.
        applicationId = "nl.leeuw008.tijdbeeld.authfix"
        minSdk = 24
        targetSdk = 32
        versionCode = 1
        versionName = "0.1.3-authfix"
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
            manifestPlaceholders["appLabel"] = "AR-TijdBeeld #151"
            // Reuse the verified #139 signing certificate; the distinct package installs side by side.
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
    // Required by ARCore Geospatial keyless authorization.
    implementation("com.google.android.gms:play-services-auth:21.6.0")
}
