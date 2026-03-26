plugins {
    id("com.gradle.shadow")
}

dependencies {
    api(platform("org.jetbrains.kotlin:kotlin-bom:1.9.25"))

    // JSON processing
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

    // JSON Schema validation
    implementation("com.github.everit-org.json-schema:org.everit.json.schema:1.0.2")

    // HTTP client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // CLI
    implementation("info.picocli:picocli:4.7.5")
    annotationProcessor("info.picocli:picocli-codegen:4.7.5")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.3")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.3")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Create shadow JAR for standalone use
tasks.register<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("pi-ai")
    mergeServiceFiles()

    manifest {
        attributes["Main-Class"] = "com.mariozechner.pi.ai.PiAi"
    }
}