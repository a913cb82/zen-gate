import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

// Traceable installs: versionCode tracks commit count, so dumpsys always tells what's live.
// Fallbacks keep tarball/shallow builds working; +dirty marks uncommitted trees.
val gitCount =
    runCatching {
        providers
            .exec {
                commandLine("git", "rev-list", "--count", "HEAD")
            }.standardOutput.asText
            .get()
            .trim()
            .toInt()
    }.getOrNull() ?: 1
val gitHash =
    runCatching {
        providers
            .exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
            }.standardOutput.asText
            .get()
            .trim()
    }.getOrNull()?.ifEmpty { "nogit" } ?: "nogit"
val gitDirty =
    runCatching {
        providers
            .exec {
                commandLine("git", "status", "--porcelain")
            }.standardOutput.asText
            .get()
            .trim()
            .isNotEmpty()
    }.getOrNull() ?: false

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.abrai.zengate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.abrai.zengate"
        minSdk = 34
        targetSdk = 36
        versionCode = gitCount
        versionName = "0.3.0-m3+$gitHash" + if (gitDirty) "+dirty" else ""
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
// AGP 9 applies KGP itself (built-in Kotlin), lazily — so configure after evaluation.
    afterEvaluate {
        configure<KotlinAndroidProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
