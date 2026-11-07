plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
