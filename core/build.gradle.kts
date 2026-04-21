plugins {
    id("java-library")
}

dependencies {
    api("org.slf4j:slf4j-api:2.0.13")
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("com.cronutils:cron-utils:9.2.1")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
