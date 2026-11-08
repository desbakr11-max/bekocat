plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.bekocat"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.bekocat"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 32-bit x86 is excluded (packaging below): its sherpa-onnx build links a different ONNX Runtime than ORT 1.30.
    }
    packaging { jniLibs { excludes += "lib/x86/**" } }
    // Per-ABI APKs (~80 MB each) plus a universal APK; Play/AAB delivery splits automatically.
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a", "x86_64"); isUniversalApk = true } }

    signingConfigs {
        // Release signing is read from environment variables (GitHub Secrets).
        // No keystore or password is ever committed to the repository.
        create("release") {
            val ks = System.getenv("BEKOCAT_KEYSTORE_PATH")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("BEKOCAT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BEKOCAT_KEY_ALIAS")
                keyPassword = System.getenv("BEKOCAT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("BEKOCAT_KEYSTORE_PATH") != null) signingConfig = signingConfigs.getByName("release")
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
        compose = true
    }
    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core:timeline"))
    implementation(project(":engine"))
    implementation(project(":ai"))
    implementation(files("../ai/libs/sherpa-onnx-static-link-onnxruntime-1.12.21.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)


    testImplementation(libs.junit)
}

tasks.named("preBuild") { dependsOn(":ai:fetchSherpa") }
