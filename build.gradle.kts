plugins {
    `java-library`
    `maven-publish`
    signing
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "io.cardinalhq"
version = "1.0.28" // bump

repositories { mavenCentral() }

// --- versions you want to embed/shade ---
val otelProto = "1.7.0-alpha"
val protobuf  = "4.31.0"

// Create a dedicated "shade" configuration and make it available for compilation only
configurations {
    create("shade")
    compileOnly { extendsFrom(getByName("shade")) }
}

dependencies {
    // your normal deps remain visible to consumers
    implementation(platform("org.apache.logging.log4j:log4j-bom:2.25.1"))
    implementation("org.apache.logging.log4j:log4j-api")
    implementation("org.apache.logging.log4j:log4j-core")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl")

    implementation("io.opentelemetry:opentelemetry-sdk:1.53.0")
    implementation("io.opentelemetry:opentelemetry-sdk-logs:1.53.0")

    // deps to embed & relocate (won't appear in published POM)
    add("shade", "io.opentelemetry.proto:opentelemetry-proto:$otelProto")
    add("shade", "com.google.protobuf:protobuf-java:$protobuf")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.test { useJUnitPlatform() }

// Build a single shaded jar that replaces the normal jar
tasks.shadowJar {
    // only embed the "shade" configuration
    configurations = listOf(project.configurations.getByName("shade"))
    archiveClassifier.set("") // publish this as the main jar
    relocate("com.google.protobuf", "io.cardinalhq.shaded.com.google.protobuf")
    relocate("io.opentelemetry.proto", "io.cardinalhq.shaded.io.opentelemetry.proto")
}
tasks.jar { enabled = false } // ensure only the shaded jar is published

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            // publish the shaded jar (already classifier = "")
            artifact(tasks.shadowJar)

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

signing {
    val key = System.getenv("GPG_PRIVATE_KEY")
    val pass = System.getenv("GPG_PASSPHRASE")
    if (!key.isNullOrBlank() && !pass.isNullOrBlank()) {
        useInMemoryPgpKeys(key, pass)
        sign(publishing.publications["mavenJava"])
    }
}