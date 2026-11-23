import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.roborazzi)
}

// Release signing comes from a git-ignored keystore.properties or from environment variables (CI); see SIGNING.md.
// Without either, release builds stop with an error: Aether never switches signing identity silently.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? = signingProps.getProperty(prop) ?: System.getenv(env)
val releaseKeystore = signingValue("storeFile", "AETHER_KEYSTORE")?.let { rootProject.file(it) }
val allowDebugSignedRelease = (project.findProperty("aether.debugSignedRelease") as String?) == "true"

android {
    namespace = "com.aether.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aether.tv"
        // Fire OS 6 (API 25) and newer. Fire OS 7 = API 28, Fire OS 8 = API 30, Fire OS 14 = API 34.
        minSdk = 25
        // Fire OS 14 devices require targetSdk 34 for new installs.
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        // Where Settings ▸ Updates looks for releases (owner/repo on GitHub).
        buildConfigField("String", "UPDATE_REPO", "\"pehdry-dot/aether-tv\"")
    }

    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = signingValue("storePassword", "AETHER_KEYSTORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "AETHER_KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "AETHER_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                releaseKeystore != null -> signingConfigs.getByName("release")
                // Only on request (-Paether.debugSignedRelease=true): a debug-signed release can't update a release-signed install.
                allowDebugSignedRelease -> signingConfigs.getByName("debug")
                else -> null
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                // Robolectric downloads android-all at test time; use the same Maven Central mirror as the build.
                it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
                it.maxHeapSize = "3g"
            }
        }
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.ui.ExperimentalComposeUiApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

roborazzi {
    outputDir.set(file("$rootDir/docs/screenshots"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.profileinstaller)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}

// A release build without a configured key fails here instead of producing an APK signed with an unexpected identity.
tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        if (releaseKeystore == null && !allowDebugSignedRelease) throw GradleException(
            "No release signing key. Create keystore.properties (see SIGNING.md) or set AETHER_KEYSTORE*, " +
                "or build with -Paether.debugSignedRelease=true to sign with this machine's debug key on purpose.",
        )
    }
}
