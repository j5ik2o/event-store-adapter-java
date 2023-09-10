import java.net.URI

plugins {
    id("com.diffplug.spotless") version "6.7.2"
    id("java")
    id("maven-publish")
}

group = "com.github.j5ik2o"
version = "event-store-adapter-java"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation("software.amazon.awssdk:dynamodb:2.20.140")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.14.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.14.2")
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
//    create<Copy>("javadocToDocsFolder") {
//        from(javadoc)
//        into("docs/javadoc")
//    }
//
//    assemble {
//        dependsOn("javadocToDocsFolder")
//    }
//
//    create<Jar>("sourcesJar") {
//        from(sourceSets.main.get().allJava)
//        archiveClassifier.set("sources")
//    }
//
//    create<Jar>("javadocJar") {
//        from(javadoc)
//        archiveClassifier.set("javadoc")
//    }
}


publishing {
    repositories {
        maven {
            val releasesRepoUrl = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotsRepoUrl = uri("https://oss.sonatype.org/content/repositories/snapshots/")
            name = "SonatypeOSS"
            url = if (project.extra["isReleaseVersion"] as Boolean) releasesRepoUrl else snapshotsRepoUrl
            credentials {
                username = System.getenv("SONATYPE_USERNAME")  // CIで環境変数を設定する
                password = System.getenv("SONATYPE_PASSWORD")  // CIで環境変数を設定する
            }
        }
    }
}