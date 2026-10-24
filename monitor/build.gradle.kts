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
val coreJar = project(":core").tasks.named<Jar>("jar")
val externalLibraries = runtimeLibraries.map { libraries -> libraries.filter { it != coreJar.get().archiveFile.get().asFile } }
val executableJar = tasks.named<Jar>("jar") {
    // Ship application code (including core) in one replaceable JAR; third-party libs stay external.
    from(project(":core").extensions.getByType<SourceSetContainer>()["main"].output)
    archiveFileName.set("rsync-airlift.jar")
    inputs.files(runtimeLibraries).withPropertyName("manifestClasspath")
    doFirst {
        manifest.attributes(
            "Main-Class" to application.mainClass.get(),
            "Class-Path" to externalLibraries.get().sortedBy { it.name }
                .joinToString(" ") { "lib/${it.name}" },
        )
    }
}

val jarBundle = tasks.register<Sync>("jarBundle") {
    group = "distribution"
    description = "Assemble executable rsync-airlift.jar with manifest-linked lib/ dependencies."
    into(layout.buildDirectory.dir("jar-bundle"))
    from(executableJar)
    into("lib") { from(externalLibraries) }
}

val jarBundleZip = tasks.register<Zip>("jarBundleZip") {
    group = "distribution"
    description = "Package the executable JAR and lib/ into a portable ZIP."
    archiveFileName.set("rsync-airlift-${project.version}-jar.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(jarBundle)
}

tasks.named("assemble") { dependsOn(jarBundleZip) }
