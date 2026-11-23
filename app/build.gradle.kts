plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.milahajek.hayek"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.milahajek.hayek"
        minSdk = 30
        targetSdk = 36
        versionCode = 23
        versionName = "1.10.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Podepisuje se jen tehdy, když je k dispozici klíč autora (není součástí repozitáře).
    // Bez něj (např. na sestavovacím serveru F-Droidu) vznikne nepodepsané APK.
    val keystore = rootProject.file("release.jks")
    val ksPass = System.getenv("KS_PASS")
    val canSign = keystore.exists() && !ksPass.isNullOrEmpty()
    signingConfigs {
        if (canSign) create("release") {
            storeFile = keystore
            storePassword = ksPass
            keyAlias = "wallet"
            keyPassword = ksPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true; all { it.systemProperty("roborazzi.test.record", "true"); it.maxHeapSize = "3g" } } }
    packaging { jniLibs { useLegacyPackaging = true } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.79.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.2.1")
    implementation("org.bitcoindevkit:bdk-android:3.1.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
