plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.dismov.safetyrecorder"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dismov.safetyrecorder"
        minSdk = 30 // Android 11, la versión del libro del curso
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // arm64: teléfonos actuales; x86_64: emulador. Las otras arquitecturas solo agrandan el APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.recyclerview)
    implementation(libs.core)
    implementation(libs.vosk.android)
    testImplementation(libs.junit)
}
