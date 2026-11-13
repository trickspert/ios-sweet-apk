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
        // Always higher than the previous build: minutes since 2026-01-01.
        versionCode = ((System.currentTimeMillis() / 60000L) - 29_455_200L).toInt()
        versionName = "0.2.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // One fixed key for every build (CI and local), so a new APK always installs
        // over the old one and keeps the imported iOS disk. Each CI runner used to
        // make its own random debug key, and updates then failed ("App not installed").
        // This is a public development key, not a secret; SWEET16_KEYSTORE overrides it.
        create("release") {
            val ks = System.getenv("SWEET16_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("SWEET16_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SWEET16_KEY_ALIAS") ?: "sweet16"
                keyPassword = System.getenv("SWEET16_KEY_PASSWORD") ?: System.getenv("SWEET16_KEYSTORE_PASSWORD")
            } else {
                storeFile = file("sweet16-dev.keystore")
                storePassword = "sweet16"
                keyAlias = "sweet16"
                keyPassword = "sweet16"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // QEMU runs as a process from nativeLibraryDir: the .so files must be
        // extracted to disk, not mapped from the APK.
        jniLibs {
            useLegacyPackaging = true
            // Ship Termux's ELF files byte for byte: AGP strips .so files when an
            // NDK is installed (CI runners have one), and any rewrite risks the
            // exec failure patchelf caused (see android/tools/fetch-termux-qemu.py).
            keepDebugSymbols += "**/*.so"
        }
    }

    // The boot bundle (ram.bin ~ tens of MB) is stored, not compressed, so it
    // copies fast on first start.
    androidResources { noCompress += listOf("bin") }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// The boot stub (tools/virt-stub/stub.bin, committed) goes into assets/boot.
// CI adds assets/boot/kernel (xnu build) and assets/boot/sweet-sh (self-test).
val stubAssets = layout.buildDirectory.dir("generated/sweet-assets").get().asFile
rootProject.file("../tools/virt-stub/stub.bin").copyTo(File(stubAssets, "boot/stub.bin"), overwrite = true)
android.sourceSets["main"].assets.srcDir(stubAssets)

dependencies {
    testImplementation("junit:junit:4.13.2")
}
