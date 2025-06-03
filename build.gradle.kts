// Top-level build file where you can add configuration options common to all sub-projects/modules.
// build.gradle.kts (проектный)
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.4.0")
        classpath("com.google.gms:google-services:4.3.15")
    }
}