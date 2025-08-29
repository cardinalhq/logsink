plugins {
    id("java-library")
    id("maven-publish")
    id("signing")
}

group = "io.cardinalhq"
version = "1.0.43"

repositories { mavenCentral() }

configurations.all {
    resolutionStrategy.force("com.google.protobuf:protobuf-java:3.25.5")
}

dependencies {
    // Align all Log4j modules; don't mix versions
    val log4j = "2.25.1"
    compileOnly(platform("org.apache.logging.log4j:log4j-bom:$log4j"))
    annotationProcessor(platform("org.apache.logging.log4j:log4j-bom:$log4j"))

    compileOnly("org.apache.logging.log4j:log4j-api")
    compileOnly("org.apache.logging.log4j:log4j-core")
    annotationProcessor("org.apache.logging.log4j:log4j-core")

    // If you still use SLF4J in non-appender classes, keep it compileOnly
    compileOnly("org.slf4j:slf4j-api:2.0.13")

    // OTLP protos only (you don't need the OTel SDK here)
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