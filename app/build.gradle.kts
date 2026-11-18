plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val fixedKeystoreFile = file("tijdbeeld-release.jks")
val hasFixedKeystore = fixedKeystoreFile.exists()

android {
    namespace = "nl.leeuw008.tijdbeeld"
    compileSdk = 35

    // Compile only the isolated launcher activity; keep the same Android build configuration.
    sourceSets.getByName("main").java.setSrcDirs(listOf("src/minimal/java"))

    defaultConfig {
        applicationId = "nl.leeuw008.tijdbeeld.diagnose"
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
            manifestPlaceholders["appLabel"] = "TijdBeeld zonder locatiebibliotheek"
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
}
