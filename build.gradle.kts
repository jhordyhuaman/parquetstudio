import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

fun properties(key: String) = project.findProperty(key).toString()

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.github.jhordyhuaman"
version = properties("pluginVersion")

repositories {
    mavenCentral()
}

dependencies {
    // DuckDB for Parquet CRUD operations
    implementation("org.duckdb:duckdb_jdbc:0.10.2")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.13.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.1")
    testImplementation("org.assertj:assertj-core:3.27.3")
    implementation("com.google.code.gson:gson:2.10.1")
}

configurations.implementation {
    exclude(module = "slf4j-api")
    exclude(module = "slf4j-log4j12")
}

// Configure Gradle IntelliJ Plugin
intellij {
    pluginName.set(properties("pluginName"))
    version.set(properties("platformVersion"))
    type.set(properties("platformType"))
    updateSinceUntilBuild.set(false)
    sameSinceUntilBuild.set(true)
}

// Ensure dependencies are included in the plugin JAR
tasks.jar {
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks {
    // Set the JVM compatibility versions
    properties("javaVersion").let {
        withType<JavaCompile> {
            sourceCompatibility = it
            targetCompatibility = it
        }
        withType<KotlinCompile> {
            kotlinOptions.jvmTarget = it
        }
    }

    wrapper {
        gradleVersion = properties("gradleVersion")
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    patchPluginXml {
        version.set(properties("pluginVersion"))
        sinceBuild.set(properties("pluginSinceBuild"))
        changeNotes.set(properties("changeNotes"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
