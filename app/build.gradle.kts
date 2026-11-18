plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.leeuw008.installatietest"
    compileSdk = 35

    defaultConfig {
        applicationId = "nl.leeuw008.installatietest"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Use a separate source root so none of the original TijdBeeld classes
    // are compiled into this diagnostic APK.
    sourceSets.getByName("main").java.setSrcDirs(listOf("src/minimal/java"))

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
