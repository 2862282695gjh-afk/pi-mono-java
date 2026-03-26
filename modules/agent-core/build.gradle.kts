plugins {
    id("com.gradle.shadow")
}

dependencies {
    api(project(":modules:ai"))

    // Reactive streams
    implementation("org.reactivestreams:reactive-streams:1.0.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-flow:1.8.1")

    // CLI
    implementation("info.picocli:picocli:4.7.5")
    annotationProcessor("info.picocli:picocli-codegen:4.7.5")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.3")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.3")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// Create shadow JAR for standalone use
tasks.register<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("pi-agent-core")
    mergeServiceFiles()

    manifest {
        attributes["Main-Class"] = "com.mariozechner.pi.agent.PiAgentCore"
    }
}