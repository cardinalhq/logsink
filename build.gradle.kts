import org.gradle.api.tasks.bundling.Jar

plugins {
    `java-library`
    `maven-publish`
    signing
    id("com.gradleup.shadow") version "8.3.5"
}

group = "io.cardinalhq"
version = "1.0.60"

repositories {
    mavenCentral()
}

configurations.all {
    resolutionStrategy.force("com.google.protobuf:protobuf-java:3.25.5")
}

dependencies {
    val log4j = "2.25.1"
    val openTelemetry = "1.64.0"

    compileOnly(platform("org.apache.logging.log4j:log4j-bom:$log4j"))
    annotationProcessor(platform("org.apache.logging.log4j:log4j-bom:$log4j"))

    compileOnly("org.apache.logging.log4j:log4j-api")
    compileOnly("org.apache.logging.log4j:log4j-core")
    annotationProcessor("org.apache.logging.log4j:log4j-core")

    // If you still use SLF4J in non-appender classes, keep it compileOnly
    compileOnly("org.slf4j:slf4j-api:2.0.13")

    api(platform("io.opentelemetry:opentelemetry-bom:$openTelemetry"))
    api("io.opentelemetry:opentelemetry-api")
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }
    implementation("io.opentelemetry:opentelemetry-exporter-sender-jdk")
    implementation("io.opentelemetry.proto:opentelemetry-proto:1.3.2-alpha")

    implementation("com.lmax:disruptor:4.0.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("com.lmax.disruptor", "io.cardinalhq.logsink.shaded.disruptor")
    mergeServiceFiles()
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("unshaded")
}

tasks.register<Copy>("copyShaded") {
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.get().archiveFile)
    into(layout.buildDirectory.dir("dist"))
    rename { "logsink-${project.version}.jar" }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
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

tasks.test { useJUnitPlatform() }
