plugins {
    id("java")
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testImplementation("org.mockito:mockito-core:5.11.0")

    // SLF4J API (you log against this interface)
    implementation("org.slf4j:slf4j-api:2.0.13")

    // Bridge: SLF4J -> Log4j2 (implementation of LoggerFactory)
    runtimeOnly("org.apache.logging.log4j:log4j-slf4j2-impl:2.22.1")

    // Log4j2 core (does the actual logging)
    runtimeOnly("org.apache.logging.log4j:log4j-core:2.22.1")

    implementation("io.opentelemetry:opentelemetry-sdk:1.49.0")
    implementation("io.opentelemetry:opentelemetry-sdk-logs:1.49.0")
    implementation("io.opentelemetry.proto:opentelemetry-proto:1.5.0-alpha")
}

tasks.test {
    useJUnitPlatform()
}