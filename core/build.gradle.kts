plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api("com.anthropic:anthropic-java:3.12.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // AgentsDocTest reads these, so the tests rerun when they change even if no code did.
    inputs.files(
        rootProject.files(
            "AGENTS.md", "CLAUDE.md", "build.gradle.kts", "app/build.gradle.kts",
            "gradle/wrapper/gradle-wrapper.properties", "app/src/main/java/dev/aiauto/data/Stores.kt",
        ),
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file(".github")).withPathSensitivity(PathSensitivity.RELATIVE)
}
