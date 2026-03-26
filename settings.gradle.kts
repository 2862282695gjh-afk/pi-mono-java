rootProject.name = "pi-mono-java"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    ":modules:pi-ai",
    ":modules:pi-agent-core",
    ":modules:pi-coding-agent",
    ":modules:pi-tui"
)

project(":modules:pi-ai").projectDir = file("modules/ai")
project(":modules:pi-agent-core").projectDir = file("modules/agent-core")
project(":modules:pi-coding-agent").projectDir = file("modules/coding-agent-cli")
project(":modules:pi-tui").projectDir = file("modules/tui")
