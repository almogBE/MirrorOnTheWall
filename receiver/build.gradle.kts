plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mirroronthewall.receiver"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mirroronthewall.receiver"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"

        ndk {
            // Many Android TV devices still run a 32-bit (armeabi-v7a) userspace.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
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
    implementation(project(":common"))
    implementation(libs.webrtc)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
}
