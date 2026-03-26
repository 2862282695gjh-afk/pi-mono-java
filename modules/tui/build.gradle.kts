plugins {
    id("com.gradle.shadow")
}

dependencies {
    // Terminal UI
    implementation("org.jline:jline-terminal:3.26.2")
    implementation("org.jline:jline-terminal-jansi:3.26.2")
    implementation("org.jline:jline-reader:3.26.2")
    implementation("org.jline:jline-console:3.26.2")

    // Terminal GUI
    implementation("com.googlecode.lanterna:lanterna-3.1.2")

    // CLI
    implementation("info.picocli:picocli:4.7.5")
    annotationProcessor("info.picocli:picocli-codegen:4.7.5")

    // Reactive streams
    implementation("org.reactivestreams:reactive-streams:1.0.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.3")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.3")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")
}

// Create shadow JAR for standalone use
tasks.register<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("pi-tui")
    mergeServiceFiles()

    manifest {
        attributes["Main-Class"] = "com.mariozechner.pi.tui.PiTui"
    }
}