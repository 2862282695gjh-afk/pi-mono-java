rootProject.name = "pi-mono-java"

// Enable new Gradle layout conventions
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Define modules
include(
    ":modules:ai",
    ":modules:agent-core",
    ":modules:coding-agent-cli",
    ":modules:tui",
    ":modules:web-ui",
    ":modules:pods"
)

// Module path shortcuts
project(":modules:ai").projectDir = file("modules/ai")
project(":modules:agent-core").projectDir = file("modules/agent-core")
project(":modules:coding-agent-cli").projectDir = file("modules/coding-agent-cli")
project(":modules:tui").projectDir = file("modules/tui")
project(":modules:web-ui").projectDir = file("modules/web-ui")
project(":modules:pods").projectDir = file("modules/pods")