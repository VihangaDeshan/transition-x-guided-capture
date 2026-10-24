// SPDX-License-Identifier: Apache-2.0
// Plain JVM build of the platform-independent packages (core, signals, guidance, replay, export).
// It compiles them WITHOUT the Android SDK, so an android.* or ARCore import in those packages
// breaks this build: that is how "no platform types in the decision engine" (NFR-10) is enforced.
//
//   gradle -p tools/engine-tests test
plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

val base = "../../modules/guided-capture/android/src"
val purePackages = listOf("core", "signals", "guidance", "replay", "export")

kotlin {
    sourceSets {
        getByName("main") {
            kotlin.setSrcDirs(purePackages.map { "$base/main/java/lk/transitionx/capture/$it" })
        }
        getByName("test") {
            kotlin.setSrcDirs(listOf("$base/test/java"))
        }
    }
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Offline replay of a recorded session:  gradle -p tools/engine-tests replay -Pquality=/path/quality.jsonl
tasks.register<JavaExec>("replay") {
    group = "verification"
    description = "Replays quality.jsonl through the decision engine"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("lk.transitionx.capture.replay.ReplayRunnerKt")
    val q = (project.findProperty("quality") as String?) ?: ""
    val t = (project.findProperty("thresholds") as String?) ?: file("../../config/thresholds.v1.json").absolutePath
    args = listOf(q, t)
}
