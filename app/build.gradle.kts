import org.gradle.api.tasks.Exec
import java.util.Properties

plugins {
    id("com.android.application")
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
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.skytr1x.obhod"
        minSdk = 30
        targetSdk = 37
        ndkVersion = "27.2.12479018"
        versionCode = 1
        versionName = "1.6"

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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

androidComponents {
    beforeVariants { variantBuilder ->
        variantBuilder.javaClass.methods.find { it.name == "setUnitTestEnabled" }?.invoke(variantBuilder, true)
    }
}

// Build the Telegram proxy's Rust cdylib with the Android NDK and package it
// from the generated directory (the source tree stays free of build outputs).
val rustCrateDir = rootProject.file("native/tgwsproxy")
val rustTargetDir = layout.buildDirectory.dir("rust/target")
val rustJniLibsDir = layout.buildDirectory.dir("generated/rust/jniLibs")

android.sourceSets.getByName("main").jniLibs.srcDir(rustJniLibsDir.get().asFile)

fun registerRustBuild(name: String, target: String, abi: String, clangName: String) =
    tasks.register<Exec>(name) {
        val ndkDir = androidComponents.sdkComponents.ndkDirectory.get().asFile
        val ndkBinDir = ndkDir.resolve("toolchains/llvm/prebuilt/${
            when {
                System.getProperty("os.name").lowercase().contains("win") -> "windows-x86_64"
                System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
                else -> "linux-x86_64"
            }
        }/bin")
        val llvmAr = ndkBinDir.resolve(if (System.getProperty("os.name").lowercase().contains("win")) "llvm-ar.exe" else "llvm-ar")
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
    "buildTelegramRustArm64", "aarch64-linux-android", "arm64-v8a", "aarch64-linux-android30-clang"
)
val buildRustArm32 = registerRustBuild(
    "buildTelegramRustArm32", "armv7-linux-androideabi", "armeabi-v7a", "armv7a-linux-androideabi30-clang"
)
val buildTelegramRust = tasks.register("buildTelegramRust") {
    dependsOn(buildRustArm64, buildRustArm32)
}
tasks.named("preBuild").configure {
    dependsOn(buildTelegramRust)
}

val sulinuxCoreDir = rootProject.file("core")
val sulinuxAssetsDir = layout.buildDirectory.dir("generated/sulinux/assets")

android.sourceSets.getByName("main").assets.srcDir(sulinuxAssetsDir.get().asFile)

val sulinuxSources = listOf(
    "src/sulinux/main.cpp",
    "src/socks/socks5_server.cpp",
    "src/desync/desync_engine.cpp",
    "src/desync/tls_utils.cpp",
    "src/strategy/fake_payloads.cpp",
    "src/strategy/profile.cpp",
    "src/strategy/strategy_selector.cpp",
    "src/util/host_list.cpp",
    "src/util/json.cpp"
)

fun registerSulinuxBuild(name: String, abi: String, clangName: String) =
    tasks.register<Exec>(name) {
        val ndkDir = androidComponents.sdkComponents.ndkDirectory.get().asFile
        val ndkBinDir = ndkDir.resolve("toolchains/llvm/prebuilt/${
            when {
                System.getProperty("os.name").lowercase().contains("win") -> "windows-x86_64"
                System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
                else -> "linux-x86_64"
            }
        }/bin")
        val clang = ndkBinDir.resolve(clangName + if (System.getProperty("os.name").lowercase().contains("win")) ".cmd" else "")
        val includeDir = sulinuxCoreDir.resolve("include")
        val sources = sulinuxSources.map { sulinuxCoreDir.resolve(it) }
        val output = sulinuxAssetsDir.get().dir("sulinux/bin/$abi/sulinux").asFile

        inputs.files(sources)
        inputs.dir(includeDir)
        outputs.file(output)
        workingDir(sulinuxCoreDir)
        doFirst { output.parentFile.mkdirs() }
        commandLine(buildList {
            add(clang.absolutePath)
            addAll(
                listOf(
                    "-std=c++17", "-O2", "-s", "-fPIE", "-pie",
                    "-D_GNU_SOURCE=1", "-DSKY_HOST_BUILD=1",
                    "-Wall", "-Wextra", "-Wno-unused-parameter",
                    "-ffunction-sections", "-fdata-sections",
                    "-Wl,--gc-sections", "-Wl,-z,max-page-size=16384",
                    "-static-libstdc++", "-pthread",
                    "-I", includeDir.absolutePath
                )
            )
            sources.forEach { add(it.absolutePath) }
            add("-o")
            add(output.absolutePath)
            add("-latomic")
        })
    }

val buildSulinuxArm64 = registerSulinuxBuild(
    "buildSulinuxArm64", "arm64-v8a", "aarch64-linux-android30-clang++"
)
val buildSulinuxArm32 = registerSulinuxBuild(
    "buildSulinuxArm32", "armeabi-v7a", "armv7a-linux-androideabi30-clang++"
)
val buildSulinuxX64 = registerSulinuxBuild(
    "buildSulinuxX64", "x86_64", "x86_64-linux-android30-clang++"
)
val buildSulinux = tasks.register("buildSulinux") {
    dependsOn(buildSulinuxArm64, buildSulinuxArm32, buildSulinuxX64)
}
tasks.named("preBuild").configure {
    dependsOn(buildSulinux)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("net.java.dev.jna:jna:5.16.0@aar")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
