plugins {
    id("java-library")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    compileOnly("net.fabricmc:fabric-loader:0.19.2")
    implementation(project(":core"))
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

tasks.shadowJar {
    archiveClassifier.set("")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to project.version))
    }
}
