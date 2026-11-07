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
        versionCode = 2
        versionName = "1.1"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
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
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}

tasks.named("preBuild") {
    dependsOn(":jvmJar")
}
