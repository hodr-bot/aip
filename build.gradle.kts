plugins {
    kotlin("multiplatform") version "2.0.21"
}

repositories {
    mavenCentral()
}

kotlin {
    jvm()

    js(IR) {
        nodejs()
        binaries.library()
    }

    sourceSets {
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest") {
    useJUnitPlatform()
}
