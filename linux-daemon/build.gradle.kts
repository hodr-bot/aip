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
    mainClass.set("fr.hodr.pia.DaemonMainKt")
}

tasks.test {
    useJUnitPlatform()
}
