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
        versionCode = 1
        versionName = "0.2.0-phase2"

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
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH") ?: "${rootDir}/release.keystore"
            var releasePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            if (releasePassword.isNullOrBlank()) {
                val console = System.console()
                if (console != null) {
                    val chars = console.readPassword("Enter Release Keystore Password: ")
                    if (chars != null) releasePassword = String(chars)
                }
            }
            storeFile = file(keystorePath)
            storePassword = releasePassword ?: ""
            keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "antitheft_release"
            keyPassword = releasePassword ?: ""
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
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
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
