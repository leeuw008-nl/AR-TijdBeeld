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
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }

    // Compile only the one test Activity. The original TijdBeeld source tree
    // remains untouched in this isolated branch but is excluded from this APK.
    sourceSets.getByName("main").java.setSrcDirs(
        listOf("src/main/java/nl/leeuw008/installatietest")
    )

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
