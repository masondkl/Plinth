plugins {
    kotlin("jvm").version("1.9.0")
    id("org.gradle.maven-publish")
}

group = "com.github.masondkl.plinth"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.github.masondkl.plinth"
            artifactId = "Plinth"
            version = "1.0.0"
            from(components["java"])
        }
    }
}