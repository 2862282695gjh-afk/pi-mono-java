plugins {
    kotlin("jvm") version "1.9.25" apply false
    kotlin("plugin.serialization") version "1.9.25" apply false
    application
    id("com.gradle.shadow") version "8.1.1" apply false
    idea
    eclipse
}

// Java toolchain
val javaVersion by extra(JavaVersion.VERSION_11)
val toolchainVersion by extra("11")

allprojects {
    group = "com.mariozechner.pi"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "kotlin")
    apply(plugin = "kotlin-spring")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(toolchainVersion))
        }
        withSourcesJar()
    }

    tasks {
        compileJava {
            options.encoding = "UTF-8"
            options.compilerArgs.add("-parameters")
        }

        compileKotlin {
            kotlinOptions {
                jvmTarget = "11"
                apiVersion = "1.9"
                languageVersion = "1.9"
                freeCompilerArgs = listOf(
                    "-Xjsr305=strict",
                    "-Xjdk-enum-compare=true"
                )
            }
        }
    }

    // Common dependencies
    dependencies {
        implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.9.25"))
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
        testImplementation(kotlin("test"))
        testImplementation(kotlin("test-junit"))
    }
}

// Root project configuration
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

tasks.test {
    useJUnitPlatform()
}