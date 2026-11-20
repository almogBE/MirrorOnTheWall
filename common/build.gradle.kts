import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM module: signaling protocol, pairing, WebSocket transport,
// constants. It has no Android dependencies, so all of it is unit-tested on
// the JVM, including real loopback socket tests of the signaling server/client.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.java.websocket)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
