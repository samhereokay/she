plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.antitheft.guardian"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.antitheft.guardian"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0-phase2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("direct") {
            dimension = "distribution"
            versionNameSuffix = "-direct"
        }
        create("play") {
            dimension = "distribution"
            versionNameSuffix = "-play"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH") ?: "${rootDir}/release.keystore"
    val keystoreFile = file(keystorePath)
    val releasePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: "sheantitheft2026"

    signingConfigs {
        create("release") {
            if (keystoreFile.exists() && !releasePassword.isNullOrBlank()) {
                storeFile = keystoreFile
                storePassword = releasePassword
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "antitheft_release"
                keyPassword = releasePassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (keystoreFile.exists() && !releasePassword.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        ignoreTestSources = true
    }
}

dependencies {
    implementation(project(":common-core"))
    implementation(project(":device-owner"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Room Database with KSP
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Pinned SQLCipher for Room Database Encryption
    implementation("net.zetetic:sqlcipher-android:4.5.5")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Fused Location Provider Client
    implementation("com.google.android.gms:play-services-location:21.3.0")


    // WorkManager for Persistent Offline Sync
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Network Ingestion
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // QR Code Scanning
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Unit & Instrumentation Tests
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
