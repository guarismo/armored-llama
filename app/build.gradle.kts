import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

plugins {
    // AGP 9 compiles Kotlin via built-in support; only the compose compiler plugin is applied.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.iguar.armedllama"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.iguar.armedllama"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            // Extract the bundled llama-server .so to nativeLibraryDir so it can be exec'd.
            useLegacyPackaging = true
        }
    }
}

// Stage the pinned llama.cpp arm64 server + its shared libs into jniLibs. Uses Gradle's built-in
// tarTree/gzip so no extra plugin is needed. Idempotent: skips if libllamaserver.so already present.
val llamaRelease = "b9775"
val llamaUrl =
    "https://github.com/ggml-org/llama.cpp/releases/download/$llamaRelease/llama-$llamaRelease-bin-android-arm64.tar.gz"
val jniArm64 = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")

val fetchLlamaServer by tasks.registering {
    description = "Download + stage the llama.cpp $llamaRelease arm64 server into jniLibs"
    val marker = jniArm64.file("libllamaserver.so").asFile
    outputs.file(marker)
    doLast {
        if (marker.exists()) return@doLast
        val tarball = layout.buildDirectory.file("llama-dl/llama-$llamaRelease.tar.gz").get().asFile
        if (!tarball.exists()) {
            tarball.parentFile.mkdirs()
            URI(llamaUrl).toURL().openStream().use { input: java.io.InputStream ->
                tarball.outputStream().use { out: java.io.OutputStream -> input.copyTo(out) }
            }
        }
        copy {
            from(tarTree(resources.gzip(tarball)))
            into(jniArm64)
            include("**/*.so", "**/llama-server")
            eachFile { path = name } // flatten the llama-bXXXX/ prefix
            includeEmptyDirs = false
        }
        val server = jniArm64.file("llama-server").asFile
        if (server.exists()) server.renameTo(jniArm64.file("libllamaserver.so").asFile)
    }
}

tasks.named("preBuild") { dependsOn(fetchLlamaServer) }

// AGP 9 built-in Kotlin: the old android.kotlinOptions{} DSL moves to kotlin.compilerOptions{}.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // Provides the Theme.Material3.* XML base theme referenced by res/values/themes.xml.
    implementation(libs.material)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
