plugins {
    id("com.android.application")
}

android {
    namespace = "dev.stillhome.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.stillhome.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // Fonts and symbols are read straight from the APK.
        noCompress += listOf("ttf", "png")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
