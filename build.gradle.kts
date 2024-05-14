plugins {
    kotlin("jvm").version("1.9.0")
    id("org.gradle.maven-publish")
}

group = "me.mason"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}