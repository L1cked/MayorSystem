import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.compile.JavaCompile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    // Newer Kotlin Gradle Plugin avoids Gradle 8.14+ deprecation warnings (and is future-proof for Gradle 10).
    kotlin("jvm") version "2.3.0"
    // Shadow plugin (fat jar + relocation). "com.github.johnrengelman.shadow" is unmaintained.
    // We use the maintained GradleUp coordinates.
    id("com.gradleup.shadow") version "9.3.1"
}

group = "mayorSystem"
version = "1.1.1"

// Capture once during configuration so task actions don't reach for Task.project at execution time.
val pluginVersion = project.version.toString()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.lucko.me/")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            // Kotlin 2.x deprecated -Xjvm-default and replaced it with -jvm-default.
            // The modern supported modes are: disable, enable, no-compatibility.
            "-jvm-default=enable",
            "-Xjsr305=strict"
        )
    }
}

dependencies {
    // Paper API (provided by the server)
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")

    // PlaceholderAPI (optional, for /papi placeholders)
    compileOnly("me.clip:placeholderapi:2.11.6")
    // LuckPerms API (optional, for elected mayor username prefix)
    compileOnly("net.luckperms:api:5.4")

    // Kotlin runtime (Paper does not ship Kotlin)
    implementation(kotlin("stdlib"))

    // Cloud v2 (shaded into the jar via Shadow)
    // NOTE: Cloud v2 "standard" parsers live in cloud-core (transitive via cloud-paper),
    // so we DON'T need a separate cloud-parser-standard dependency.
    val cloudVersion = "2.0.0-beta.14"
    implementation(platform("org.incendo:cloud-minecraft-bom:$cloudVersion"))
    implementation("org.incendo:cloud-paper:$cloudVersion")

    // Audit log JSONL serialization/deserialization
    implementation("com.google.code.gson:gson:2.11.0")

    // SQLite (data store)
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")

    // MySQL (data store)
    implementation("com.mysql:mysql-connector-j:8.4.0")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Coroutines (async IO + main-thread hop helpers)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("me.clip:placeholderapi:2.11.6")
}

tasks.processResources {
    filteringCharset = "UTF-8"

    // Allow plugin.yml to reference ${version}
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.withType<ShadowJar>().configureEach {
    // Dev/testing shadow jar; keep thin jar as the main release artifact.
    archiveClassifier.set("dev")
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE

    // Include license/notice files in the shaded jar.
    from("LICENSE") {
        into("META-INF")
    }
    from("NOTICE") {
        into("META-INF")
    }

    // Shade Cloud into your jar so server owners don't need to install it separately.
    relocate("org.incendo.cloud", "mayorSystem.shaded.cloud")

    // Cloud depends on geantyref (safe to relocate too).
    relocate("io.leangen.geantyref", "mayorSystem.shaded.geantyref")
}

tasks.test {
    useJUnitPlatform()
}
