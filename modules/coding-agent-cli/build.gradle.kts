plugins {
    application
    id("com.gradle.shadow")
}

dependencies {
    api(project(":modules:ai"))
    api(project(":modules:agent-core"))
    api(project(":modules:tui"))
    api(project(":modules:pods"))

    // CLI framework
    implementation("info.picocli:picocli:4.7.5")
    annotationProcessor("info.picocli:picocli-codegen:4.7.5")

    // JSON processing
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

    // Configuration
    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.typesafe:config:1.4.3")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.12")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.3")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.3")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

application {
    mainClass.set("com.mariozechner.pi.codingagent.PiCodingAgent")
}

applicationDefaultJvmArgs = listOf(
    "-Xmx4g",
    "-Xms2g",
    "-XX:+UseG1GC",
    "-XX:+UseStringDeduplication"
)

// Create fat JAR with all dependencies
tasks.register<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("pi-coding-agent")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "com.mariozechner.pi.codingagent.PiCodingAgent"
    }
}