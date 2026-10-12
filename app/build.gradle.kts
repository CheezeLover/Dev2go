plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Bundles the arm64 opencode binary when one has been built.
 *
 * scripts/build-agent.sh produces dist/opencode-arm64. It is ~192 MB, so it is not
 * required to build: without it the app still runs, and the agent can be imported from
 * device storage instead. Tying it to a file that may or may not exist keeps a fresh
 * clone buildable without a 192 MB prerequisite, while a release build that has run the
 * script ships fully self-contained.
 *
 * Staged into a generated directory rather than pointed at dist/ directly — that directory
 * also holds the signed APK, and assets.srcDir would happily bundle the APK inside itself.
 */
val agentAssetStage = layout.buildDirectory.dir("generated/agentAssets")
val bundledAgent = rootProject.file("dist/opencode-arm64")

// No onlyIf predicate: capturing a script-level File in a task predicate breaks the
// configuration cache, and `from` on a missing file is already a no-op.
val stageAgentBinary by tasks.registering(Copy::class) {
    from(bundledAgent)
    into(agentAssetStage)
}

android {
    sourceSets.getByName("main") {
        // builtBy is required, not decorative: Gradle validates that a task consuming this
        // directory declares the dependency, and lint's report model fails the build
        // otherwise.
        assets.srcDir(files(agentAssetStage).builtBy(stageAgentBinary))
    }
    namespace = "com.dev2go"
    // 35, matching AGP 8.7.3. Compiling against 36 needs a newer AGP than the version
    // pinned here, and nothing in the app uses an API 36 feature.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dev2go"
        // 28, not lower: the embedded opencode binary is a Bun cross-compile and Bun's
        // android runtime is built for API 28 ("for Android 28" in the ELF note). Going
        // below it would mean rebuilding Bun itself.
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // arm64-v8a covers every current phone and is where the opencode binary is built.
        // x86_64 is kept so the app can be run on an emulator; both are built in release
        // too, so a test build and a release build behave the same.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += "-std=c++17"
                cppFlags += "-O3"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // llama.cpp and the JNI shim are the app; stripping them breaks the symbols.
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}