plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Native audio is deliberately opt-in. Keeping the externalNativeBuild block
// behind this property means the normal APK build does not require an NDK or
// CMake installation, and the existing AudioRecord path remains the default.
val nativeAudioEnabled = (project.findProperty("acousticguard.nativeAudio") as? String)
    ?.equals("true", ignoreCase = true) == true

android {
    namespace = "com.acousticguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.acousticguard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("boolean", "NATIVE_AUDIO_ENABLED", nativeAudioEnabled.toString())

    }

    if (nativeAudioEnabled) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        noCompress.add("tflite")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.tflite)
    implementation(libs.play.services.location)
    testImplementation(libs.junit)
}
