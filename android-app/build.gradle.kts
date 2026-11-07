plugins {
    id("com.android.application")
    kotlin("android")
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "pia.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "pia.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(files("$rootDir/build/libs/pia-core-jvm.jar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
}

tasks.named("preBuild") {
    dependsOn(":jvmJar")
}
