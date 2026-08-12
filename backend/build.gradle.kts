import org.gradle.internal.os.OperatingSystem

plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.serviceatlas"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")

    // HOCON parsing for application.conf / reference.conf (FR-3.2).
    implementation("com.typesafe:config:1.4.5")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:postgresql:1.21.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---------------------------------------------------------------------------
// Frontend bundling (§2 Packaging: one artifact serves the built Angular app).
//
// `-PskipFrontend` skips the npm build, for fast backend-only iteration and for
// environments without a network. The jar is then backend-only.
// ---------------------------------------------------------------------------
val frontendDir = rootProject.layout.projectDirectory.dir("frontend")
val frontendDist = frontendDir.dir("dist/service-atlas/browser")
val skipFrontend = providers.gradleProperty("skipFrontend").isPresent
val npmCommand = if (OperatingSystem.current().isWindows) "npm.cmd" else "npm"

val npmInstall by tasks.registering(Exec::class) {
    group = "frontend"
    description = "Installs frontend dependencies."
    workingDir = frontendDir.asFile
    commandLine(npmCommand, "install", "--no-audit", "--no-fund")
    inputs.file(frontendDir.file("package.json"))
    inputs.file(frontendDir.file("package-lock.json")).optional(true)
    outputs.dir(frontendDir.dir("node_modules"))
    onlyIf { !skipFrontend && frontendDir.file("package.json").asFile.exists() }
}

val buildFrontend by tasks.registering(Exec::class) {
    group = "frontend"
    description = "Builds the Angular production bundle."
    dependsOn(npmInstall)
    workingDir = frontendDir.asFile
    commandLine(npmCommand, "run", "build")
    inputs.dir(frontendDir.dir("src"))
    inputs.file(frontendDir.file("package.json"))
    outputs.dir(frontendDist)
    onlyIf { !skipFrontend && frontendDir.file("package.json").asFile.exists() }
}

tasks.named<ProcessResources>("processResources") {
    if (!skipFrontend) {
        dependsOn(buildFrontend)
        from(frontendDist) {
            into("static")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Testcontainers-backed tests are opt-in: they need a working Docker daemon.
    if (!providers.gradleProperty("withTestcontainers").isPresent) {
        exclude("**/*ContainerTest.class")
    }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required = true
        html.required = true
    }
}

// Q-1: >= 75% line coverage on parser/, graph/ and export/.
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    violationRules {
        listOf("parser", "graph", "export").forEach { pkg ->
            rule {
                element = "PACKAGE"
                includes = listOf("com.serviceatlas.$pkg", "com.serviceatlas.$pkg.*")
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "0.75".toBigDecimal()
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "service-atlas.jar"
}
