plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("fr.hodr.aip.DaemonMainKt")
}

tasks.test {
    useJUnitPlatform()
}
