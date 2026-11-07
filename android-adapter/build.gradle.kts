plugins {
    kotlin("jvm")
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
