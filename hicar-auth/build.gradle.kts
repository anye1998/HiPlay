plugins {
    // No version here: the Android Gradle plugin is already on the build classpath through the
    // application plugin that the other modules apply, and a versioned request would conflict.
    id("com.android.library")
}

// The HiChain engine is third-party C source that is fetched, not committed:
// `python3 scripts/fetch-hichain-sources.py`. Everything here degrades to "engine unavailable"
// when it has not been fetched, so an ordinary source checkout still configures and builds.
val engineDirectory = file("src/main/cpp/third_party/deviceauth")
val engineFetched = engineDirectory.isDirectory

android {
    namespace = "com.shilapi.xcertplay.hicarauth"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        buildConfigField("boolean", "ENGINE_BUILT", engineFetched.toString())
        // Both native libraries find their Kotlin side by fully qualified name, so neither the class
        // nor the native methods may be renamed if shrinking is ever switched on. Nothing shrinks
        // today; this keeps a later build-type change from turning into a link error at runtime.
        consumerProguardFiles("consumer-rules.pro")
        if (engineFetched) {
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }
        }
    }

    if (engineFetched) {
        ndkVersion = "28.2.13676358"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation(libs.junit)
}
