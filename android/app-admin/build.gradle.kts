plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.antitheft.admin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.antitheft.admin"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
        ?: (project.findProperty("RELEASE_KEYSTORE_PATH") as? String)
        ?: "${System.getProperty("user.home")}/.android/release.keystore"
    val keystoreFile = if (file(keystorePath).exists()) file(keystorePath) else file("${rootDir}/release.keystore")
    val releasePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
        ?: (project.findProperty("RELEASE_KEYSTORE_PASSWORD") as? String)

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

    // QR Code Generation
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Biometric Authentication
    implementation("androidx.biometric:biometric:1.1.0")

    // Encrypted Shared Preferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
