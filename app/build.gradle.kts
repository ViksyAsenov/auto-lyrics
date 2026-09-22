plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.viksy.autolyrics"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.viksy.autolyrics"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.car.app:app:1.7.0")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation(libs.material)
    implementation("androidx.media:media:1.8.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.json:json:20260814")
}