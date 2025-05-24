plugins {
    `java-library`
    `maven-publish`
    signing
}

group = "io.cardinalhq"
version = "1.0.0" // or whatever release

java {
    withJavadocJar()
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("logsink")
                description.set("A lightweight OTLP logs exporter for Java using OpenTelemetry.")
                url.set("https://github.com/cardinalhq/logsink")

                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }

                developers {
                    developer {
                        id.set("ruchir")
                        name.set("Ruchir Jha")
                        email.set("ruchir@cardinalhq.io")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/cardinalhq/logsink.git")
                    developerConnection.set("scm:git:ssh://git@github.com:cardinalhq/logsink.git")
                    url.set("https://github.com/cardinalhq/logsink")
                }
            }
        }
    }

    repositories {
        maven {
            name = "MavenCentral"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = "token" // literally "token"
                password = System.getenv("MAVEN_CENTRAL_TOKEN")
            }
        }
    }
}

signing {
    useInMemoryPgpKeys(
        System.getenv("GPG_PRIVATE_KEY"),
        System.getenv("GPG_PASSPHRASE")
    )
    sign(publishing.publications["mavenJava"])
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

    // Jackson
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.0")

    implementation("io.opentelemetry:opentelemetry-sdk:1.49.0")
    implementation("io.opentelemetry:opentelemetry-sdk-logs:1.49.0")
    implementation("io.opentelemetry.proto:opentelemetry-proto:1.5.0-alpha")
}

tasks.test {
    useJUnitPlatform()
}