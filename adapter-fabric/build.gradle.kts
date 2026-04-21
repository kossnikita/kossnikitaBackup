import org.gradle.api.JavaVersion
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("java-library")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    compileOnly("net.fabricmc:fabric-loader:0.19.2")
    compileOnly("com.mojang:brigadier:1.0.18")
    implementation(project(":core"))
}

tasks.shadowJar {
    archiveClassifier.set("")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

extensions.configure<JavaPluginExtension> {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    withSourcesJar()
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to project.version))
    }
}
