dependencies {
    implementation(project(":core"))
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
    implementation("org.apache.commons:commons-compress:1.26.0")
}
