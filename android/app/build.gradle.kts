plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aiaa4435.camera"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aiaa4435.camera"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "P2-v9"

        // 只打包真机架构：ML Kit 的 native 库每个 ABI 约 6~12 MB，
        // 四个 ABI 全打进去 APK 会到 52 MB；去掉 x86/x86_64（模拟器专用）能省 23 MB。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    val camerax = "1.3.1"
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // P3：主体检测 + 帧间跟踪。
    // 用 bundled 版本（模型打包进 APK，离线可用，不需要首次联网下载）。
    // 注意：不要引入 object-detection-custom —— 它会把完整训练框架
    // （TensorFlow Lite + 图像处理全家桶）拖进来，APK 从 6MB 涨到 52MB。
    implementation("com.google.mlkit:object-detection:17.0.2")

    testImplementation("junit:junit:4.13.2")
}
