import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension

plugins {
    `java-library`
    `maven-publish`
    signing
    id("com.diffplug.spotless") version "8.11.0"
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

group = "io.github.j5ik2o"
version = File("./version").readText().trim()
extra["isReleaseVersion"] = !version.toString().endsWith("SNAPSHOT")

val driverModules = listOf("core", "dynamodb", "spanner", "bigtable", "sqlite")

val coreDependencies =
    listOf(
        "api:com.fasterxml.jackson.core:jackson-databind:2.22.4",
        "implementation:com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.4",
        "implementation:org.slf4j:slf4j-api:2.0.20",
        "implementation:com.google.code.findbugs:jsr305:3.0.2",
    )
val driverDependencies =
    mapOf(
        "core" to coreDependencies,
        "dynamodb" to
            listOf(
                "api:software.amazon.awssdk:dynamodb:2.57.11",
                "implementation:org.slf4j:slf4j-api:2.0.20",
            ),
        "spanner" to
            listOf(
                "api:com.google.cloud:google-cloud-spanner:6.127.0",
                "implementation:org.slf4j:slf4j-api:2.0.20",
                "implementation:com.google.code.findbugs:jsr305:3.0.2",
            ),
        "bigtable" to
            listOf(
                "api:com.google.cloud:google-cloud-bigtable:2.88.0",
                "implementation:org.slf4j:slf4j-api:2.0.20",
                "implementation:com.google.code.findbugs:jsr305:3.0.2",
            ),
        "sqlite" to
            listOf(
                "api:org.xerial:sqlite-jdbc:3.54.1.0",
                "implementation:org.slf4j:slf4j-api:2.0.20",
                "implementation:com.google.code.findbugs:jsr305:3.0.2",
            ),
    )
val moduleDescriptions =
    mapOf(
        "core" to "Event Store Adapter for Java: core types and memory storage",
        "dynamodb" to "Event Store Adapter for Java: DynamoDB storage",
        "spanner" to "Event Store Adapter for Java: Spanner storage",
        "bigtable" to "Event Store Adapter for Java: Bigtable storage",
        "sqlite" to "Event Store Adapter for Java: SQLite storage",
    )
val repositoryUrl = "https://github.com/j5ik2o/event-store-adapter-java"
val scmUrl = "scm:git:git@github.com:j5ik2o/event-store-adapter-java.git"

allprojects {
    repositories {
        mavenCentral()
    }
}

fun MavenPublication.applyCommonPom(projectName: String, descriptionText: String) {
    pom {
        name.set(projectName)
        packaging = "jar"
        description.set(descriptionText)
        url.set(repositoryUrl)
        licenses {
            license {
                name.set("The MIT License")
                url.set("https://opensource.org/license/mit/")
            }
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("j5ik2o")
                name.set("Junichi Kato")
                email.set("j5ik2o@gmail.com")
            }
        }
        scm {
            connection.set(scmUrl)
            developerConnection.set(scmUrl)
            url.set(repositoryUrl)
        }
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")
    apply(plugin = "com.diffplug.spotless")

    group = rootProject.group
    version = rootProject.version
    extra["isReleaseVersion"] = rootProject.extra["isReleaseVersion"]

    val moduleName = name
    val artifactName = "event-store-adapter-java-$moduleName"
    val coreModule = rootProject.project(":core")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(11))
        }
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    dependencies {
        if (moduleName != "core") {
            "api"(coreModule)
        }
        driverDependencies.getValue(moduleName).forEach { notation ->
            val separator = notation.indexOf(':')
            add(notation.substring(0, separator), notation.substring(separator + 1))
        }
    }

    configure<SpotlessExtension> {
        java {
            googleJavaFormat()
        }
    }

    tasks.withType<Jar> {
        archiveBaseName.set(artifactName)
    }

    tasks.withType<JavaCompile> {
        options.release.set(11)
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:deprecation")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    tasks.withType<Sign> {
        onlyIf { project.extra["isReleaseVersion"] as Boolean }
    }

    val mainSourceSet = extensions.getByType<SourceSetContainer>()["main"]
    val javadocTask = tasks.named("javadoc")
    val sourcesJarTask =
        tasks.register<Jar>("sourcesJar") {
            from(mainSourceSet.allJava)
            archiveClassifier.set("sources")
        }
    val javadocJarTask =
        tasks.register<Jar>("javadocJar") {
            from(javadocTask)
            archiveClassifier.set("javadoc")
        }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("mavenJava") {
                from(components["java"])
                artifactId = artifactName
                artifact(sourcesJarTask.get())
                artifact(javadocJarTask.get())
                setVersion(project.version)
                applyCommonPom(artifactName, moduleDescriptions.getValue(moduleName))
            }
        }
    }

    configure<SigningExtension> {
        val signingKey = System.getenv("SIGNING_KEY")
        val signingPassword = System.getenv("SIGNING_PASSWORD")
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(the<PublishingExtension>().publications["mavenJava"])
    }
}

dependencies {
    driverModules.forEach { "api"(project(":$it")) }

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("ch.qos.logback:logback-classic:1.6.6")
    testImplementation("org.testcontainers:testcontainers:2.0.5")
    testImplementation("software.amazon.awssdk:apache5-client:2.57.11")
    testImplementation("software.amazon.awssdk:netty-nio-client:2.57.11")

    testImplementation("com.networknt:json-schema-validator:2.0.8")
    testImplementation("com.google.code.findbugs:jsr305:3.0.2")
}

configure<SpotlessExtension> {
    java {
        googleJavaFormat()
        target(
            "src/main/java/**/*.java",
            "src/test/java/**/*.java",
            "src/coreDynamoDbOnlyTest/java/**/*.java",
            "src/coreSqliteOnlyTest/java/**/*.java",
            "src/coreSpannerOnlyTest/java/**/*.java",
            "src/coreBigtableOnlyTest/java/**/*.java",
        )
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

val testJavaVersion = providers.gradleProperty("testJavaVersion").map(String::toInt).orElse(11)
val testJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(testJavaVersion.map(JavaLanguageVersion::of))
}

val googleSdkSets =
    mapOf(
        "5.1" to mapOf("google-cloud-spanner" to "6.124.0", "google-cloud-bigtable" to "2.85.0"),
        "5.2" to mapOf("google-cloud-spanner" to "6.127.0", "google-cloud-bigtable" to "2.88.0"),
    )
val googleSdkSet =
    providers.gradleProperty("googleSdkSet").orNull?.let { name ->
        googleSdkSets[name]
            ?: throw GradleException("googleSdkSet must be one of ${googleSdkSets.keys}, not '$name'")
    }

// Only the test runtime changes: the library stays compiled against the declared SDK, as the
// published jar is, and runs on the SDK that a user who pins the selected release set has.
configurations.testRuntimeClasspath {
    googleSdkSet?.forEach { (module, version) ->
        resolutionStrategy.force("com.google.cloud:$module:$version")
    }
}

val coreDynamoDbOnlyTestSourceSet =
    sourceSets.create("coreDynamoDbOnlyTest") {
        java.srcDir("src/coreDynamoDbOnlyTest/java")
        resources.srcDir("src/coreDynamoDbOnlyTest/resources")
    }

dependencies {
    "coreDynamoDbOnlyTestImplementation"(project(":core"))
    "coreDynamoDbOnlyTestImplementation"(project(":dynamodb"))
    "coreDynamoDbOnlyTestImplementation"("org.junit.jupiter:junit-jupiter-api:5.14.4")
    "coreDynamoDbOnlyTestRuntimeOnly"("org.junit.jupiter:junit-jupiter-engine:5.14.4")
    "coreDynamoDbOnlyTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "coreDynamoDbOnlyTestImplementation"("ch.qos.logback:logback-classic:1.6.6")
    "coreDynamoDbOnlyTestImplementation"("org.testcontainers:testcontainers:2.0.5")
    "coreDynamoDbOnlyTestImplementation"("software.amazon.awssdk:apache5-client:2.57.11")
}

val coreDynamoDbOnlyTest =
    tasks.register<Test>("coreDynamoDbOnlyTest") {
        description =
            "Runs the DynamoDB public entry point with only core and dynamodb on the classpath."
        group = "verification"
        javaLauncher.set(testJavaLauncher)
        testClassesDirs = coreDynamoDbOnlyTestSourceSet.output.classesDirs
        classpath = coreDynamoDbOnlyTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        outputs.upToDateWhen { false }
        testLogging {
            showStandardStreams = true
            events = setOf(TestLogEvent.STARTED, TestLogEvent.SKIPPED, TestLogEvent.PASSED, TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

val coreSqliteOnlyTestSourceSet =
    sourceSets.create("coreSqliteOnlyTest") {
        java.srcDir("src/coreSqliteOnlyTest/java")
        resources.srcDir("src/coreSqliteOnlyTest/resources")
    }

dependencies {
    "coreSqliteOnlyTestImplementation"(project(":core"))
    "coreSqliteOnlyTestImplementation"(project(":sqlite"))
    "coreSqliteOnlyTestImplementation"("org.junit.jupiter:junit-jupiter-api:5.14.4")
    "coreSqliteOnlyTestRuntimeOnly"("org.junit.jupiter:junit-jupiter-engine:5.14.4")
    "coreSqliteOnlyTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val coreSqliteOnlyTest =
    tasks.register<Test>("coreSqliteOnlyTest") {
        description =
            "Checks the SQLite public entry point with only core and sqlite on the classpath."
        group = "verification"
        javaLauncher.set(testJavaLauncher)
        testClassesDirs = coreSqliteOnlyTestSourceSet.output.classesDirs
        classpath = coreSqliteOnlyTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        outputs.upToDateWhen { false }
        testLogging {
            showStandardStreams = true
            events = setOf(TestLogEvent.STARTED, TestLogEvent.SKIPPED, TestLogEvent.PASSED, TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

val coreSpannerOnlyTestSourceSet =
    sourceSets.create("coreSpannerOnlyTest") {
        java.srcDir("src/coreSpannerOnlyTest/java")
        resources.srcDir("src/coreSpannerOnlyTest/resources")
    }

dependencies {
    "coreSpannerOnlyTestImplementation"(project(":core"))
    "coreSpannerOnlyTestImplementation"(project(":spanner"))
    "coreSpannerOnlyTestImplementation"("org.junit.jupiter:junit-jupiter-api:5.14.4")
    "coreSpannerOnlyTestRuntimeOnly"("org.junit.jupiter:junit-jupiter-engine:5.14.4")
    "coreSpannerOnlyTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val coreSpannerOnlyTest =
    tasks.register<Test>("coreSpannerOnlyTest") {
        description =
            "Checks the Spanner public entry point with only core and spanner on the classpath."
        group = "verification"
        javaLauncher.set(testJavaLauncher)
        testClassesDirs = coreSpannerOnlyTestSourceSet.output.classesDirs
        classpath = coreSpannerOnlyTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        outputs.upToDateWhen { false }
        testLogging {
            showStandardStreams = true
            events = setOf(TestLogEvent.STARTED, TestLogEvent.SKIPPED, TestLogEvent.PASSED, TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

val coreBigtableOnlyTestSourceSet =
    sourceSets.create("coreBigtableOnlyTest") {
        java.srcDir("src/coreBigtableOnlyTest/java")
        resources.srcDir("src/coreBigtableOnlyTest/resources")
    }

dependencies {
    "coreBigtableOnlyTestImplementation"(project(":core"))
    "coreBigtableOnlyTestImplementation"(project(":bigtable"))
    "coreBigtableOnlyTestImplementation"("org.junit.jupiter:junit-jupiter-api:5.14.4")
    "coreBigtableOnlyTestRuntimeOnly"("org.junit.jupiter:junit-jupiter-engine:5.14.4")
    "coreBigtableOnlyTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val coreBigtableOnlyTest =
    tasks.register<Test>("coreBigtableOnlyTest") {
        description =
            "Checks the Bigtable public entry point with only core and bigtable on the classpath."
        group = "verification"
        javaLauncher.set(testJavaLauncher)
        testClassesDirs = coreBigtableOnlyTestSourceSet.output.classesDirs
        classpath = coreBigtableOnlyTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        outputs.upToDateWhen { false }
        testLogging {
            showStandardStreams = true
            events = setOf(TestLogEvent.STARTED, TestLogEvent.SKIPPED, TestLogEvent.PASSED, TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

tasks {

    withType<Test> {
        javaLauncher.set(testJavaLauncher)
        useJUnitPlatform()
        outputs.upToDateWhen { false }
        doFirst {
            val launcher = javaLauncher.get()
            logger.lifecycle("Test JVM: JDK ${launcher.metadata.languageVersion} (${launcher.executablePath})")
        }
        testLogging {
            showStandardStreams = true
            events = setOf(TestLogEvent.STARTED, TestLogEvent.SKIPPED, TestLogEvent.PASSED, TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    named<Test>("test") {
        googleSdkSet?.forEach { (module, version) -> systemProperty("googleSdkSet.$module", version) }
    }

    named<Javadoc>("javadoc") {
        dependsOn(subprojects.map { "${it.path}:classes" })
        source(subprojects.map { it.extensions.getByType<SourceSetContainer>()["main"].allJava })
        classpath = files(subprojects.map { it.extensions.getByType<SourceSetContainer>()["main"].compileClasspath })
    }

    register<Copy>("javadocToDocsFolder") {
        from(named("javadoc"))
        into("docs/javadoc")
    }

    assemble {
        dependsOn("javadocToDocsFolder")
        dependsOn(subprojects.map { "${it.path}:assemble" })
    }

    check {
        dependsOn(subprojects.map { "${it.path}:check" })
        dependsOn(coreDynamoDbOnlyTest)
        dependsOn(coreSqliteOnlyTest)
        dependsOn(coreSpannerOnlyTest)
        dependsOn(coreBigtableOnlyTest)
    }

    register<Jar>("sourcesJar") {
        from(sourceSets.main.get().allJava)
        archiveClassifier.set("sources")
    }

    register<Jar>("javadocJar") {
        from(javadoc)
        archiveClassifier.set("javadoc")
    }

    withType<Sign> {
        onlyIf { project.extra["isReleaseVersion"] as Boolean }
    }

    withType<Wrapper> {
        gradleVersion = "9.9.0"
    }

    withType<JavaCompile> {
        options.release.set(11)
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:deprecation")
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "event-store-adapter-java"
            artifact(tasks["sourcesJar"])
            artifact(tasks["javadocJar"])

            setVersion(project.version)
            applyCommonPom("event-store-adapter-java", "Event Store Adapter for Java")
        }
    }
}

nexusPublishing {
    this.repositories {
        this.sonatype {
            packageGroup = "io.github.j5ik2o"
            nexusUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/")
            username = System.getenv("SONATYPE_USERNAME")
            password = System.getenv("SONATYPE_PASSWORD")
        }
    }
}

signing {
    val signingKey = System.getenv("SIGNING_KEY")
    val signingPassword = System.getenv("SIGNING_PASSWORD")
    useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications["mavenJava"])
}
