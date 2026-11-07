plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))
    implementation(project(":android-adapter"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
