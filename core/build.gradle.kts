plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(21)
    if (providers.gradleProperty("enableWindowsNative").orNull == "true") mingwX64()
    // Opt in while Linux actual adapters are being implemented; retain the working JVM build.
    if (providers.gradleProperty("enableLinuxNative").orNull == "true") linuxX64 {
        compilations.getByName("main").cinterops.create("airliftPosix") {
            defFile(project.file("src/linuxX64Main/cinterop/airlift_posix.def"))
            includeDirs(project.file("src/linuxX64Main/cinterop"))
        }
    }
    jvm {
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-io-core:0.7.0")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            runtimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
        }
    }
}

tasks.named<Jar>("jvmJar") {
    archiveFileName.set("rsync-airlift-core.jar")
    val libraries = configurations.named("jvmRuntimeClasspath")
    inputs.property("manifestClasspath", libraries.map { files -> files.files.sortedBy { it.name }.joinToString(" ") { "lib/${it.name}" } })
    doFirst {
        manifest.attributes("Class-Path" to libraries.get().files.sortedBy { it.name }
            .joinToString(" ") { "lib/${it.name}" })
    }
}

// Keep existing build commands working; KMP's actual tasks are jvmJar/jvmTest.
tasks.register("jar") { group = "build"; dependsOn("jvmJar") }
tasks.register("test") { group = "verification"; dependsOn("jvmTest") }
