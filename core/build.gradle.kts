plugins {
    kotlin("jvm")
    `java-library`
}

kotlin { jvmToolchain(21) }

dependencies {
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test { useJUnitPlatform() }

tasks.named<Jar>("jar") {
    archiveFileName.set("rsync-airlift-core.jar")
    val libraries = configurations.runtimeClasspath
    inputs.property("manifestClasspath", libraries.map { files -> files.files.sortedBy { it.name }.joinToString(" ") { "lib/${it.name}" } })
    doFirst {
        manifest.attributes("Class-Path" to libraries.get().files.sortedBy { it.name }
            .joinToString(" ") { "lib/${it.name}" })
    }
}
