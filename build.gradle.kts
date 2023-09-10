plugins {
    id("com.diffplug.spotless") version "6.21.0"
    id("java")
    id("maven-publish")
    signing
}

group = "com.github.j5ik2o"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation("software.amazon.awssdk:dynamodb:2.20.144")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.2")
    implementation("org.slf4j:slf4j-api:1.7.32")
    testImplementation("ch.qos.logback:logback-classic:1.3.1")
    testImplementation("org.testcontainers:testcontainers:1.19.0")
    testImplementation("org.testcontainers:junit-jupiter:1.19.0")
    testImplementation("org.testcontainers:localstack:1.19.0")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("de.huxhorn.sulky:de.huxhorn.sulky.ulid:8.3.0")

}


spotless {
    java {
        googleJavaFormat()
    }
}

extra["isReleaseVersion"] = !version.toString().endsWith("SNAPSHOT")

repositories {
    mavenCentral()
    maven {
        url = uri("https://oss.sonatype.org/content/repositories/snapshots")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks {
    withType<Test> {
        useJUnitPlatform()
    }

    create<Copy>("javadocToDocsFolder") {
        from(javadoc)
        into("docs/javadoc")
    }

    assemble {
        dependsOn("javadocToDocsFolder")
    }

    create<Jar>("sourcesJar") {
        from(sourceSets.main.get().allJava)
        archiveClassifier.set("sources")
    }

    create<Jar>("javadocJar") {
        from(javadoc)
        archiveClassifier.set("javadoc")
    }

    withType<Sign> {
        onlyIf { project.extra["isReleaseVersion"] as Boolean }
    }

    withType<Wrapper> {
        gradleVersion = "8.0"
    }

    withType<JavaCompile> {
        options.compilerArgs.add("-Xlint:deprecation")
    }
}


publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(tasks["sourcesJar"])
            artifact(tasks["javadocJar"])
            setVersion(project.version)
            pom {
                name.set(project.name)
                description.set("Event Store Adapter for Java")
                url.set("https://github.com/j5ik2o/event-store-adapter-java")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
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
            repositories {
                // To locally check out the poms
                maven {
                    val releasesRepoUrl = uri("$buildDir/repos/releases")
                    val snapshotsRepoUrl = uri("$buildDir/repos/snapshots")
                    name = "BuildDir"
                    url = if (project.extra["isReleaseVersion"] as Boolean) releasesRepoUrl else snapshotsRepoUrl
                }
                maven {
                    val releasesRepoUrl = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2/")
                    val snapshotsRepoUrl = uri("https://oss.sonatype.org/content/repositories/snapshots/")
                    name = "SonatypeOSS"
                    url = if (project.extra["isReleaseVersion"] as Boolean) releasesRepoUrl else snapshotsRepoUrl
                    credentials {
                        username = System.getenv("SONATYPE_USERNAME")
                        password = System.getenv("SONATYPE_PASSWORD")
                    }
                }
            }
        }
    }
}

signing {
    sign(publishing.publications["mavenJava"])
}