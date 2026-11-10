plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystore = providers.environmentVariable("ORBBUDDY_RELEASE_KEYSTORE").orNull
val releaseStorePassword = providers.environmentVariable("ORBBUDDY_RELEASE_STORE_PASSWORD").orNull
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
if (releaseRequested && (releaseKeystore.isNullOrBlank() || releaseStorePassword.isNullOrBlank())) {
    throw GradleException("Release signing requires ORBBUDDY_RELEASE_KEYSTORE and ORBBUDDY_RELEASE_STORE_PASSWORD")
}

android {
    namespace = "dev.orbbuddy.android"
    compileSdk = 34

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "dev.orbbuddy.android"
        minSdk = 30
        targetSdk = 34
        versionCode = 7
        versionName = "0.4.3"
    }

    signingConfigs {
        if (!releaseKeystore.isNullOrBlank() && !releaseStorePassword.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releaseStorePassword
                keyAlias = "orbbuddy-release"
                keyPassword = releaseStorePassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!releaseKeystore.isNullOrBlank() && !releaseStorePassword.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
