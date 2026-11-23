plugins {
    id("com.android.application")
}

android {
    namespace = "app.hexforandroid"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "app.hexforandroid"
        minSdk = 30
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Release signing comes from the environment (android/release.sh reads the
    // keystore password from the keyring). Without it, release is unsigned.
    val keystore = System.getenv("HEX_RELEASE_KEYSTORE")
    if (keystore != null) {
        signingConfigs.create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("HEX_RELEASE_PASSWORD")
            keyAlias = System.getenv("HEX_RELEASE_ALIAS") ?: "hex"
            keyPassword = System.getenv("HEX_RELEASE_PASSWORD")
        }
    }

    buildTypes {
        release {
            // JNI looks HexCore's natives up by name; nothing to gain from shrinking.
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// libhexcore.so comes from the sibling core/ crate, through cargo-ndk.
// Pass -PskipCore to package whatever jniLibs already holds.
// Everything below resolves from this project, not from the build's root: the
// same directory builds as a subproject of the fork and on its own, because a
// checkout that holds only android/ (a phone's, Android Studio's) is its own
// root, and rootProject would then look for src/ and core/ one level too high.
val androidDir = file("..").normalize()
// Hex's own text steps are compiled straight into the core, from src/dictation/.
val dictationSrc = listOf(androidDir.resolveSibling("src/dictation"), androidDir.resolve("src/dictation"))
    .firstOrNull { it.isDirectory }
val buildCore = tasks.register<Exec>("buildCore") {
    description = "Builds Hex's Rust core for arm64-v8a (two CPU variants)"
    workingDir = androidDir
    commandLine("bash", androidDir.resolve("build-core.sh").absolutePath)
    onlyIf { !project.hasProperty("skipCore") }
    // Up to date unless the core, Hex's text steps or the script changed.
    inputs.dir(androidDir.resolve("core/src"))
    inputs.files(
        androidDir.resolve("core/Cargo.toml"),
        androidDir.resolve("core/Cargo.lock"),
        androidDir.resolve("core/build.rs"),
        androidDir.resolve("build-core.sh"),
    )
    if (dictationSrc != null) {
        inputs.dir(dictationSrc)
    } else {
        // A checkout without Hex's own sources can't build the core. Say so
        // plainly, instead of Gradle's "input file does not exist".
        doFirst {
            throw GradleException(
                "The Rust core compiles Hex's own text steps from src/dictation/, " +
                    "which this checkout doesn't have (looked in " +
                    "${androidDir.parentFile}/src/dictation). Build from the whole fork " +
                    "checked out, or run with -PskipCore to package the APK with the " +
                    "libhexcore*.so files already in app/src/main/jniLibs.",
            )
        }
    }
    outputs.dir(file("src/main/jniLibs"))
}
tasks.named("preBuild") { dependsOn(buildCore) }

// -PskipCore packages whatever jniLibs holds. Say so when that is nothing: the
// APK installs and its settings work, but no dictation can transcribe without
// libhexcore_dotprod.so or libhexcore_base.so beside it.
if (project.hasProperty("skipCore") && file("src/main/jniLibs").walk().none { it.isFile && it.extension == "so" }) {
    logger.warn(
        "app/src/main/jniLibs holds no libhexcore*.so (-PskipCore): this APK installs " +
            "but cannot transcribe. Build the core first (./gradlew assembleDebug, in a " +
            "checkout of the whole fork), or copy the two .so files into " +
            "app/src/main/jniLibs/arm64-v8a.",
    )
}
