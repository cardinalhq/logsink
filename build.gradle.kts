plugins {
    id("java")
    id("maven-publish")
    id("signing")
}

group = "io.cardinalhq"
version = "1.0.40"

repositories {
    mavenCentral()
}

configurations.all {
    resolutionStrategy.force("com.google.protobuf:protobuf-java:3.25.5")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testImplementation("org.mockito:mockito-core:5.11.0")


    implementation(platform("org.apache.logging.log4j:log4j-bom:2.25.1"))

    val log4j = "2.25.1"
    implementation(platform("org.apache.logging.log4j:log4j-bom:$log4j"))
    annotationProcessor(platform("org.apache.logging.log4j:log4j-bom:$log4j"))

    implementation("org.apache.logging.log4j:log4j-api")
    implementation("org.apache.logging.log4j:log4j-core")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl")

    // versionless is fine now because the BOM also covers annotationProcessor:
    annotationProcessor("org.apache.logging.log4j:log4j-core")

    // SLF4J API (you log against this interface)
    implementation("org.slf4j:slf4j-api:2.0.13")

    // Bridge: SLF4J -> Log4j2 (implementation of LoggerFactory)
    runtimeOnly("org.apache.logging.log4j:log4j-slf4j2-impl:2.22.1")

    // Log4j2 core (does the actual logging)
    runtimeOnly("org.apache.logging.log4j:log4j-core:2.22.1")

    implementation("io.opentelemetry:opentelemetry-sdk:1.49.0")
    implementation("io.opentelemetry:opentelemetry-sdk-logs:1.49.0")
    implementation("io.opentelemetry.proto:opentelemetry-proto:1.3.2-alpha")
}

// Generate -sources.jar and -javadoc.jar
java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "logsink"
            pom {
                name.set("logsink")
                description.set("Cardinal LogSink Appender and OTLP exporter")
                url.set("https://github.com/cardinalhq/logsink")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("ruchirjha")
                        name.set("Ruchir Jha")
                        email.set("rjha@cardinalhq.io")
                        organization.set("CardinalHQ, Inc.")
                        organizationUrl.set("https://cardinalhq.io")
                    }
                }
                scm {
                    url.set("https://github.com/cardinalhq/logsink")
                    connection.set("scm:git:https://github.com/cardinalhq/logsink.git")
                    developerConnection.set("scm:git:ssh://github.com:cardinalhq/logsink.git")
                }
            }
        }
    }
}

// Optional: sign only when keys are present (Central release)
signing {
    val key = System.getenv("GPG_PRIVATE_KEY")
    val pass = System.getenv("GPG_PASSPHRASE")
    if (!key.isNullOrBlank() && !pass.isNullOrBlank()) {
        useInMemoryPgpKeys(key, pass)
        sign(publishing.publications["mavenJava"])
    }
}

tasks.test {
    useJUnitPlatform()
}