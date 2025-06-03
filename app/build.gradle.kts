plugins {
    id("com.android.application")
    // Аналогично – Kotlin Android
    kotlin("android") version "1.9.10"
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.crowds"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.crowds"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"//?
    }

    buildTypes {
        release {// create("release") ?
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11 // was VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_11 // was VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "11" // was 1.8
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    // AndroidX + Material
    implementation(libs.core.ktx)            // "androidx.core:core-ktx:1.10.1"
    implementation(libs.appcompat)           // "androidx.appcompat:appcompat:1.6.1"
    implementation(libs.material)            // "com.google.android.material:material:1.9.0"
    implementation(libs.recyclerview)        // "androidx.recyclerview:recyclerview:1.3.0"
    implementation("androidx.preference:preference-ktx:1.2.0")


    // OsmDroid (карта)
    implementation(libs.osmdroid)            // "org.osmdroid:osmdroid-android:6.4.3"

    // Google Play Location
    implementation(libs.play.location)       // "com.google.android.gms:play-services-location:21.0.1"

    // Firebase (через BOM + модули)
    implementation(platform(libs.firebase.bom))   // "com.google.firebase:firebase-bom:32.1.1"
    implementation(libs.firebase.auth)            // "com.google.firebase:firebase-auth-ktx"
    implementation(libs.firebase.firestore)       // "com.google.firebase:firebase-firestore-ktx"

    // Kotlin Coroutines
    implementation(libs.coroutines.android)       // "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1"

    // Тестовые библиотеки
    testImplementation(libs.junit)                // "junit:junit:4.13.2"
    androidTestImplementation(libs.junit.ext)     // "androidx.test.ext:junit:1.1.5"
    androidTestImplementation(libs.espresso)      // "androidx.test.espresso:espresso-core:3.5.1"

    // 1) Firebase UI Auth (для простого Google Sign-In)
    implementation("com.firebaseui:firebase-ui-auth:8.0.2")

    // 2) Google Play Services (для GoogleSignInClient, если хотите кастомную реализацию)
    implementation("com.google.android.gms:play-services-auth:20.7.0")


}