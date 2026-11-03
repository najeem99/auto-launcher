plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.najib.carwelcome"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.najib.carwelcome"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK can be sideloaded without a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
