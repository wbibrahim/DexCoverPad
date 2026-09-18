plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.dex_touchpad"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.dex_touchpad"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    val releaseStoreFile = file("keystore.jks")
    val releaseKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull
    val releaseStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull
    val hasReleaseSigning = releaseStoreFile.isFile && listOf(
        releaseKeyAlias,
        releaseKeyPassword,
        releaseStorePassword
    ).all { !it.isNullOrBlank() }

    val releaseSigningConfig = if (hasReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = releaseStoreFile
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            storePassword = releaseStorePassword
        }
    } else {
        null
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Keep local release builds convenient while using the real key in CI.
            signingConfig = releaseSigningConfig ?: signingConfigs.getByName("debug")
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
        aidl = true
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/libdextouchpad.so"
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)
}
