plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.echoradio.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.echoradio.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "3.1"
        // Offline voice engine is bundled for 64-bit ARM phones (practically every phone from the last 6+ years)
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    packaging {
        jniLibs { useLegacyPackaging = true }   // store the voice engine compressed: smaller download
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
    // Spotify App Remote SDK: drop spotify-app-remote-release-*.aar into app/libs (see README)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation("com.google.code.gson:gson:2.10.1") // required by App Remote

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.browser:browser:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
}
