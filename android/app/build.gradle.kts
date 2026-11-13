plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.iossweet.sweet16"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.iossweet.sweet16"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // CI passes a keystore through the environment; otherwise the debug key signs.
        val ks = System.getenv("SWEET16_KEYSTORE")
        if (ks != null && file(ks).exists()) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("SWEET16_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SWEET16_KEY_ALIAS") ?: "sweet16"
                keyPassword = System.getenv("SWEET16_KEY_PASSWORD") ?: System.getenv("SWEET16_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // QEMU runs as a process from nativeLibraryDir: the .so files must be
        // extracted to disk, not mapped from the APK.
        jniLibs { useLegacyPackaging = true }
    }

    // The boot bundle (ram.bin ~ tens of MB) is stored, not compressed, so it
    // copies fast on first start.
    androidResources { noCompress += listOf("bin") }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
