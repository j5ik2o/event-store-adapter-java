plugins {
    id("com.diffplug.spotless") version "6.7.2"
    id("java")
}

group = "com.github.j5ik2o"
version = "event-store-adapter-java"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation("software.amazon.awssdk:dynamodb:2.20.144")
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

tasks.test {
    useJUnitPlatform()
}

spotless {
    java {
        googleJavaFormat()
    }
}