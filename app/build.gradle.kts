import org.gradle.api.tasks.Exec
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningFile = rootProject.file("keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningFile.isFile) {
        releaseSigningFile.inputStream().use { load(it) }
    }
}
if (releaseSigningFile.isFile) {
    val requiredProperties = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    val missingProperties = requiredProperties.filter {
        releaseSigningProperties.getProperty(it).isNullOrBlank()
    }
    check(missingProperties.isEmpty()) {
        "Missing ${missingProperties.joinToString()} in ${releaseSigningFile.name}"
    }
}

android {
    namespace = "com.skytr1x.obhod"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.skytr1x.obhod"
        minSdk = 26
        targetSdk = 34
        ndkVersion = "27.2.12479018"
        versionCode = 1
        versionName = "1.4.1"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }

    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    if (releaseSigningFile.isFile) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
        }
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("../core/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Build the Telegram proxy's Rust cdylib with the Android NDK and package it
// from the generated directory (the source tree stays free of build outputs).
val rustCrateDir = rootProject.file("native/tgwsproxy")
val rustTargetDir = layout.buildDirectory.dir("rust/target")
val rustJniLibsDir = layout.buildDirectory.dir("generated/rust/jniLibs")
val ndkBinDir = android.ndkDirectory.resolve("toolchains/llvm/prebuilt/${
    when {
        System.getProperty("os.name").lowercase().contains("win") -> "windows-x86_64"
        System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
}/bin")
val llvmAr = ndkBinDir.resolve(if (System.getProperty("os.name").lowercase().contains("win")) "llvm-ar.exe" else "llvm-ar")

android.sourceSets.getByName("main").jniLibs.srcDir(rustJniLibsDir)

fun registerRustBuild(name: String, target: String, abi: String, clangName: String) =
    tasks.register<Exec>(name) {
        val clang = ndkBinDir.resolve(clangName + if (System.getProperty("os.name").lowercase().contains("win")) ".cmd" else "")
        val targetEnv = target.uppercase().replace('-', '_')
        val library = rustTargetDir.get().file("$target/release/libtgwsproxy.so").asFile
        val abiLibrary = rustJniLibsDir.get().file("$abi/libtgwsproxy.so").asFile

        workingDir(rustCrateDir)
        commandLine(
            "cargo", "build", "--release", "--locked",
            "--manifest-path", rustCrateDir.resolve("Cargo.toml").absolutePath,
            "--target", target,
            "--target-dir", rustTargetDir.get().asFile.absolutePath
        )
        environment("CARGO_TARGET_${targetEnv}_LINKER", clang.absolutePath)
        environment("CC_$target", clang.absolutePath)
        environment("CC_${targetEnv.lowercase()}", clang.absolutePath)
        environment("AR_$target", llvmAr.absolutePath)
        environment("AR_${targetEnv.lowercase()}", llvmAr.absolutePath)
        inputs.files(fileTree(rustCrateDir.resolve("src")), rustCrateDir.resolve("Cargo.toml"), rustCrateDir.resolve("Cargo.lock"))
        outputs.files(library, abiLibrary)
        doLast {
            if (!library.isFile) throw GradleException("Rust build did not produce ${library.absolutePath}")
            copy {
                from(library)
                into(abiLibrary.parentFile)
            }
        }
    }

val buildRustArm64 = registerRustBuild(
    "buildTelegramRustArm64", "aarch64-linux-android", "arm64-v8a", "aarch64-linux-android26-clang"
)
val buildRustArm32 = registerRustBuild(
    "buildTelegramRustArm32", "armv7-linux-androideabi", "armeabi-v7a", "armv7a-linux-androideabi26-clang"
)
val buildTelegramRust = tasks.register("buildTelegramRust") {
    dependsOn(buildRustArm64, buildRustArm32)
}
tasks.named("preBuild").configure {
    dependsOn(buildTelegramRust)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.10.4")
    implementation("androidx.compose.foundation:foundation:1.10.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    testImplementation("junit:junit:4.13.2")
}
