plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.snestemplate"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.example.snestemplate"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { cppFlags += listOf("-std=c++17") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    // Final APK is named ZAMN.apk (AGP 8.x: outputFileName replaces the removed archivesBaseName)
    applicationVariants.all {
        outputs.forEach { output ->
            (output as? com.android.build.gradle.api.ApkVariantOutput)?.outputFileName = "ZAMN.apk"
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) }
}
