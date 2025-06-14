import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.register

plugins {
    `java-library`
    `maven-publish`
    signing
    id("com.diffplug.spotless") version "7.0.4"
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

group = "io.github.j5ik2o"
version = File("./version").readText().trim()
extra["isReleaseVersion"] = !version.toString().endsWith("SNAPSHOT")

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.13.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation("org.testcontainers:testcontainers:1.21.1")
    testImplementation("org.testcontainers:junit-jupiter:1.21.1")
    testImplementation("org.testcontainers:localstack:1.21.1")

    testImplementation("de.huxhorn.sulky:de.huxhorn.sulky.ulid:8.3.0")

    implementation("io.vavr:vavr:0.10.6")
    implementation("software.amazon.awssdk:dynamodb:2.31.63")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.1")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.19.1")
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
}

spotless {
    java {
        googleJavaFormat()
    }
}

repositories {
    mavenCentral()

    // 認証付き snapshot リポジトリ
    maven {
        name = "sonatypeSnapshots"
        url  = uri("https://central.sonatype.com/repository/maven-snapshots/")
        credentials {
            username = System.getenv("SONATYPE_USERNAME")
            password = System.getenv("SONATYPE_PASSWORD")
        }
        mavenContent { snapshotsOnly() }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks {

    withType<Test> {
        useJUnitPlatform()
        outputs.upToDateWhen { false }
        testLogging {
            showStandardStreams = true
            events = setOf(TestLogEvent.STARTED, TestLogEvent.SKIPPED, TestLogEvent.PASSED, TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    this.register<Copy>("javadocToDocsFolder") {
        from(javadoc)
        into("docs/javadoc")
    }

    assemble {
        dependsOn("javadocToDocsFolder")
    }

    this.register<Jar>("sourcesJar") {
        from(sourceSets.main.get().allJava)
        archiveClassifier.set("sources")
    }

    this.register<Jar>("javadocJar") {
        from(javadoc)
        archiveClassifier.set("javadoc")
    }

    withType<Sign> {
        onlyIf { project.extra["isReleaseVersion"] as Boolean }
    }

    withType<Wrapper> {
        gradleVersion = "8.14.2"
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:deprecation")
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            afterEvaluate {
                artifactId = tasks.jar.get().archiveBaseName.get()
            }
            artifact(tasks["sourcesJar"])
            artifact(tasks["javadocJar"])

            setVersion(project.version)
            pom {
                name.set(project.name)
                packaging = "jar"
                description.set("Event Store Adapter for Java")
                url.set("https://github.com/j5ik2o/event-store-adapter-java")
                licenses {
                    license {
                        name.set("The MIT License")
                        url.set("https://opensource.org/license/mit/")
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
                    connection.set("scm:git:git@github.com:j5ik2o/event-store-adapter-java.git")
                    developerConnection.set("scm:git:git@github.com:j5ik2o/event-store-adapter-java.git")
                    url.set("https://github.com/j5ik2o/event-store-adapter-java")
                }
            }
        }
    }
}

nexusPublishing {
    this.repositories {
        this.sonatype {
            packageGroup = "io.github.j5ik2o"
            nexusUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/")
            snapshotRepositoryUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
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

