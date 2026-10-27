plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// The release APK is always built unsigned, which is also what F-Droid's build server
// expects; build.sh signs it with apksigner.
android {
    namespace = "com.hatsyrei.maidnative"
    // 37 is the floor imposed by markdown-renderer since 0.43.0. targetSdk stays at 36
    // deliberately: compiling against newer APIs is independent of opting in to
    // new runtime behavior.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hatsyrei.maidnative"
        // 28: release APKs carry only a v3 signature (see build.sh).
        minSdk = 28
        targetSdk = 36
        versionCode = 26
        versionName = "1.10.1"

        // androidx.graphics and DataStore ship small native libs; only arm64 devices are targeted.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // A commit hash stamped into the APK; the source is public anyway.
            vcsInfo.include = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // AGP otherwise embeds the dependency list in the APK signing block, encrypted
    // to a Google key and readable only by Play. It is ~8 KB of padding to anyone
    // else, and F-Droid's scanner rejects it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // The in-app language switch needs every language in the install, not just the device's.
    bundle { language { enableSplit = false } }

    androidResources {
        // Only the app's own languages; drops the AndroidX libraries' other translations from resources.arsc.
        localeFilters += listOf("en", "zh-rCN")
        // OkHttp reads this only to parse cookies, and no client here has a CookieJar.
        ignoreAssetsPatterns += "!PublicSuffixDatabase.list"
    }

    packaging {
        // AGP stores dex uncompressed from minSdk 28 on, which nearly doubles the
        // download (APKs are fetched as-is from GitHub and F-Droid). Keep it compressed.
        dex {
            useLegacyPackaging = true
        }
        resources {
            // Coroutines debug agent, kotlin-reflect metadata and library version stamps: unused at runtime.
            excludes += listOf(
                "DebugProbesKt.bin",
                "kotlin/**.kotlin_builtins",
                "META-INF/*.version",
            )
        }
    }

    // Needed to strip the dependencies' native libs; must be an installed NDK.
    ndkVersion = "27.1.12297006"
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.coil3)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
