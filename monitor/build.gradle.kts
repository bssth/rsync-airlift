import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    implementation("com.googlecode.lanterna:lanterna:3.1.3")
    implementation("org.jline:jline-terminal:3.29.0")
    runtimeOnly("org.jline:jline-terminal-jni:3.29.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

application {
    mainClass.set("iris.tools.airlift.monitor.MainKt")
    applicationName = "rsync-airlift-monitor"
}

tasks.test { useJUnitPlatform() }
tasks.named<JavaExec>("run") { standardInput = System.`in` }

// A small executable JAR with adjacent dependencies; no unpacking or shading.
val runtimeLibraries = configurations.runtimeClasspath
val coreJar = project(":core").tasks.named<Jar>("jvmJar")
val externalLibraries = runtimeLibraries.map { libraries -> libraries.filter { it != coreJar.get().archiveFile.get().asFile } }
val manifestClasspath = externalLibraries.map { libraries ->
    (listOf(coreJar.get().archiveFileName.get()) + libraries.sortedBy { it.name }.map { "lib/${it.name}" }).joinToString(" ")
}
val executableJar = tasks.named<Jar>("jar") {
    // Keep monitor and core separate; resolve both core and third-party libs via the manifest.
    archiveFileName.set("rsync-airlift.jar")
    inputs.property("manifestClasspath", manifestClasspath)
    doFirst {
        manifest.attributes(
            "Main-Class" to application.mainClass.get(),
            "Class-Path" to manifestClasspath.get(),
        )
    }
}

val jarBundle = tasks.register("jarBundle") {
    group = "distribution"
    description = "Assemble separate monitor/core JARs and manifest-linked lib/ dependencies."
    dependsOn(executableJar, coreJar)
    inputs.files(executableJar, coreJar, externalLibraries)
    val bundleDirectory = layout.buildDirectory.dir("jar-bundle")
    outputs.dir(bundleDirectory)
    doLast {
        val root = bundleDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val sources = linkedMapOf(
            executableJar.get().archiveFileName.get() to executableJar.get().archiveFile.get().asFile,
            coreJar.get().archiveFileName.get() to coreJar.get().archiveFile.get().asFile,
        )
        externalLibraries.get().forEach { sources["lib/${it.name}"] = it }
        val destinations = sources.map { (name, source) -> root.resolve(name) to source.toPath() }
        // Gradle skips the whole task when possible; on changes, retain identical files too.
        destinations.forEach { (target, source) ->
            Files.createDirectories(target.parent)
            if (!Files.exists(target) || Files.mismatch(source, target) != -1L) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val expected = destinations.map { it.first }.toSet()
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it !in expected }
                .forEach { Files.delete(it) }
        }
    }
}

val jarBundleZip = tasks.register<Zip>("jarBundleZip") {
    group = "distribution"
    description = "Package the executable JAR and lib/ into a portable ZIP."
    archiveFileName.set("rsync-airlift-${project.version}-jar.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(jarBundle)
}

tasks.named("assemble") { dependsOn(jarBundleZip) }
