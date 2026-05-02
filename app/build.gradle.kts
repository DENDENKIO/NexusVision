plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.objectbox")
}

android {
    namespace = "com.nexus.vision"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nexus.vision"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_ARM_NEON=ON",
                    "-DANDROID_STL=c++_shared"
                )
                cppFlags("-std=c++17", "-O2", "-fvisibility=hidden")
            }
        }
    }

    ndkVersion = "27.0.12077973"

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.04.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.8.8")

    // NCNN
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    
    // Jetpack Glance (Widget)
    implementation("androidx.glance:glance-appwidget:1.1.1")

    // Core
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")

    // CameraX
    val cameraVer = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraVer")
    implementation("androidx.camera:camera-camera2:$cameraVer")
    implementation("androidx.camera:camera-lifecycle:$cameraVer")
    implementation("androidx.camera:camera-view:$cameraVer")

    // ML Kit Barcode / Text
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")

    // LiteRT-LM (Gemma-4-E2B) — Phase 9: AI エンジン有効化
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.2")
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")

    // ObjectBox
    implementation("io.objectbox:objectbox-android:5.3.0")
    implementation("io.objectbox:objectbox-kotlin:5.3.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // ExifInterface
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    // Coil (保持)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.1")
}