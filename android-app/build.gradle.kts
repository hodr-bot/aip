plugins {
    id("com.android.application")
    kotlin("android")
}

val appVersionName = "1.39"

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "fr.hodr.aip"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.hodr.aip"
        minSdk = 26
        targetSdk = 34
        versionCode = 40
        versionName = appVersionName
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

    // File name carries the real version: aip-vpn-<version>-<abi>.apk
    // instead of the module/split/build-type word salad.
    applicationVariants.all {
        outputs.all {
            val abi = filters.firstOrNull { it.filterType == "ABI" }?.identifier ?: "universal"
            (this as com.android.build.gradle.api.ApkVariantOutput).outputFileName =
                "aip-vpn-$appVersionName-$abi.apk"
        }
    }
}

dependencies {
    implementation(files("$rootDir/build/libs/pia-core-jvm.jar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.material:material:1.12.0")
}

tasks.named("preBuild") {
    dependsOn(":jvmJar")
}
