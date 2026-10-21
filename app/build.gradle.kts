plugins {
    id("com.android.application")
}

android {
    namespace = "dev.stillhome.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.stillhome.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
