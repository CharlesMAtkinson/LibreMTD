import java.time.LocalDate

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "org.charlesatkinson"
version = "0.2.0-SNAPSHOT"

repositories {
    mavenCentral()
}

javafx {
    version = "21.0.9"
    modules = listOf(
        "javafx.controls",
        "javafx.fxml",
        "javafx.graphics",
        "javafx.web"
    )
}

dependencies {
    // Kotlin
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx:1.9.0")

    // Database - SQLite with Exposed
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation("org.jetbrains.exposed:exposed-core:0.47.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.47.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.47.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.47.0")

    // Password Security
    implementation("org.mindrot:jbcrypt:0.4")

    // JSON Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // HTTP Client (Java 11+)
    // Built-in java.net.http.HttpClient - no additional dependency needed
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // OAuth 2.0
    implementation("com.nimbusds:oauth2-oidc-sdk:11.9.1")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("org.apache.logging.log4j:log4j-to-slf4j:2.23.1")

    // ODF
    implementation("org.apache.poi:poi-ooxml:5.2.5")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

application {
    mainClass.set("org.charlesatkinson.libremtd.LibreMTDKt")
}

// Create fat JAR for easy distribution
tasks.jar {
    manifest {
        attributes["Main-Class"] = "org.charlesatkinson.libremtd.LibreMTDKt"
    }
}

tasks.named("run") {
    dependsOn("installDist")
}

tasks.processResources {
    val projectVersion = version.toString()
    val buildDate = LocalDate.now().toString()

    inputs.property("projectVersion", projectVersion)
    inputs.property("buildDate", buildDate)

    filesMatching("build.properties") {
        expand(mapOf(
            "version" to projectVersion,
            "buildDate" to buildDate
        ))
    }
}

// Fat jar, built only for packageDistribution — a single portable jar
// someone can run with a bare "java -jar". Not used by installDist/run.
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "org.charlesatkinson.libremtd.LibreMTDKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// Task for building distributable package
tasks.register<Zip>("packageDistribution") {
    dependsOn("fatJar")

    from(tasks.named("fatJar"))
    from("README.md")
    from("LICENSE")

    destinationDirectory.set(
        layout.buildDirectory.dir("distributions")
    )
    archiveBaseName.set("LibreMTD")
    archiveVersion.set(version.toString())
}

tasks.test {
    useJUnitPlatform()
}