plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kareliusreks.xdl"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kareliusreks.xdl"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Постоянный ключ подписи, чтобы новые версии ставились поверх старых
    signingConfigs {
        create("fixed") {
            storeFile = file("signing.keystore")
            storePassword = "xdlpass123"
            keyAlias = "xdl"
            keyPassword = "xdlpass123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
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
