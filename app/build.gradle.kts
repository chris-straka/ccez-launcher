plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The word logic is the ccez-keys core (Rust, UniFFI), built read-only from
// the sibling checkout exactly as ccez-keys/android builds it. Build outputs
// stay in this repo (CARGO_TARGET_DIR below), and --locked keeps cargo from
// touching the core's Cargo.lock: that repo is never written from here.
val coreDir = rootProject.layout.projectDirectory.dir("../ccez-keys/core")
val rustJniLibs = layout.buildDirectory.dir("rustJniLibs")
val uniffiKotlin = layout.buildDirectory.dir("generated/uniffi")
val coreInputs = listOf("src", "Cargo.toml", "uniffi.toml")
// Processors to build for: the phone and the arm emulator (arm64-v8a).
// CCEZ_LAUNCHER_ABIS=arm64-v8a,x86_64 adds an Intel emulator.
val abis = (System.getenv("CCEZ_LAUNCHER_ABIS") ?: "arm64-v8a").split(',').map { it.trim() }.filter { it.isNotEmpty() }
val cargoTarget = System.getenv("CARGO_TARGET_DIR")?.let { file(it) }
    ?: rootProject.layout.projectDirectory.dir(".cargo-target").asFile

val buildRustCore by tasks.registering(Exec::class) {
    group = "rust"
    description = "Builds the ccez-keys Rust core"
    workingDir(coreDir)
    environment("CARGO_TARGET_DIR", cargoTarget.absolutePath)
    coreInputs.forEach { inputs.files(coreDir.file(it).asFile) }
    outputs.dir(rustJniLibs)
    commandLine(
        "cargo", "ndk", *abis.flatMap { listOf("-t", it) }.toTypedArray(), "--platform", "26",
        "-o", rustJniLibs.get().asFile.absolutePath,
        "build", "--release", "--lib", "--locked",
    )
}

val generateUniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generates the Kotlin bindings for the Rust core"
    dependsOn(buildRustCore)
    workingDir(coreDir)
    environment("CARGO_TARGET_DIR", cargoTarget.absolutePath)
    coreInputs.forEach { inputs.files(coreDir.file(it).asFile) }
    outputs.dir(uniffiKotlin)
    commandLine(
        "cargo", "run", "-q", "--locked", "--features", "cli", "--bin", "uniffi-bindgen", "--",
        "generate", "--no-format", "--language", "kotlin",
        "--library", cargoTarget.resolve("aarch64-linux-android/release/libccez_keys_core.so").absolutePath,
        "--out-dir", uniffiKotlin.get().asFile.absolutePath,
    )
}

android {
    namespace = "uk.ccez.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "uk.ccez.launcher"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("CCEZ_LAUNCHER_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("CCEZ_LAUNCHER_VERSION_NAME") ?: "0.1.0-dev"
        ndk { abiFilters += abis }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // One key for every build (scripts/build.sh points at it), so each
        // APK installs over the last; without it, the debug key.
        val keystore = System.getenv("CCEZ_LAUNCHER_KEYSTORE")?.let { file(it) }
        if (keystore != null && keystore.exists()) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("CCEZ_LAUNCHER_KEY_PASSWORD")
                keyAlias = "ccez-launcher"
                keyPassword = System.getenv("CCEZ_LAUNCHER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // Macrobenchmark measures this: like release, debuggable off.
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.getByName("main") {
        jniLibs.directories.add(rustJniLibs.get().asFile.absolutePath)
        kotlin.directories.add(uniffiKotlin.get().asFile.absolutePath)
    }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin { jvmToolchain(21) }

tasks.named("preBuild") { dependsOn(generateUniffiBindings) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation("${libs.jna.get()}@aar")
    // App names in the learning languages (Stage 5), translated on the phone.
    implementation(libs.mlkit.translate)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
