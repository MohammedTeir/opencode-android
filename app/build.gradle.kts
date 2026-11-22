plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val bundledRuntimeAssets = layout.buildDirectory.dir("generated/bundledRuntimeAssets")

val fetchBundledRuntimeAssets by tasks.registering(Exec::class) {
    val assetDirectory = bundledRuntimeAssets.get().asFile
    inputs.file(rootProject.file("scripts/fetch-bundled-runtime.sh"))
    outputs.dir(assetDirectory)
    commandLine("bash", rootProject.file("scripts/fetch-bundled-runtime.sh").absolutePath, assetDirectory.absolutePath)
}

android {
    namespace = "io.github.mohammedteir.opencodemobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.mohammedteir.opencodemobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0-v2.3.2"
        ndk { abiFilters += "arm64-v8a" }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    sourceSets.getByName("main").assets.srcDir(bundledRuntimeAssets)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        create("release") {
            if (!releaseKeystorePath.isNullOrBlank()) storeFile = file(releaseKeystorePath)
            if (!releaseKeystorePassword.isNullOrBlank()) storePassword = releaseKeystorePassword
            if (!releaseKeyAlias.isNullOrBlank()) keyAlias = releaseKeyAlias
            if (!releaseKeyPassword.isNullOrBlank()) keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(fetchBundledRuntimeAssets)
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.apache.commons:commons-compress:1.26.1")
    implementation("org.tukaani:xz:1.9")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
