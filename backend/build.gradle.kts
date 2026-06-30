import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.iacross"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// ---------- Dependency versions ----------
val testcontainersVersion = "1.20.4"
val jjwtVersion = "0.12.6"

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${testcontainersVersion}")
    }
}

dependencies {
    // --- Web / Core ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // --- Persistence ---
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")

    // --- Migrations ---
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // --- Security ---
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Bouncy Castle — required by Argon2PasswordEncoder
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // --- JWT ---
    implementation("io.jsonwebtoken:jjwt-api:${jjwtVersion}")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:${jjwtVersion}")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:${jjwtVersion}")

    // --- Cache / Redis ---
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // --- Observability ---
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // --- Structured logging ---
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // --- Testing ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // Forward all JVM system properties prefixed with "testcontainers" to test JVM
    systemProperty("spring.profiles.active", "test")
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("flowpilot.jar")
}
