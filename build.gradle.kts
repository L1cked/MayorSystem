import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.Delete
import org.gradle.jvm.tasks.Jar
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Zip
import org.gradle.process.ExecOperations
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import javax.inject.Inject

abstract class GpgSignCentralPortalStagingTask @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {
    @get:Internal
    abstract val stagingDirectory: DirectoryProperty

    @get:Input
    abstract val signingPassword: Property<String>

    @TaskAction
    fun sign() {
        val stagingRoot = stagingDirectory.get().asFile
        val signableFiles = stagingRoot.walkTopDown()
            .filter { it.isFile }
            .filterNot { it.extension == "asc" }
            .filterNot { it.extension == "md5" }
            .filterNot { it.extension == "sha1" }
            .filterNot { it.extension == "sha256" }
            .filterNot { it.extension == "sha512" }
            .toList()

        if (signableFiles.isEmpty()) {
            throw GradleException("No staged Maven Central files were found to sign.")
        }

        signableFiles.forEach { file ->
            execOperations.exec {
                commandLine(
                    "gpg",
                    "--batch",
                    "--yes",
                    "--pinentry-mode",
                    "loopback",
                    "--passphrase",
                    signingPassword.get(),
                    "--armor",
                    "--detach-sign",
                    file.absolutePath
                )
            }
        }
    }
}

plugins {
    // Newer Kotlin Gradle Plugin avoids Gradle 8.14+ deprecation warnings (and is future-proof for Gradle 10).
    kotlin("jvm") version "2.3.0"
    // Shadow plugin (fat jar + relocation). "com.github.johnrengelman.shadow" is unmaintained.
    // We use the maintained GradleUp coordinates.
    id("com.gradleup.shadow") version "9.4.1"
    `maven-publish`
}

group = "mayorSystem"
version = "1.1.7"

// Capture once during configuration so task actions don't reach for Task.project at execution time.
val pluginVersion = project.version.toString()
// Only bump after manually running publish-maven-central-api for the new API artifact version.
val apiPublicationVersion = "1.1.5"
val centralPortalStagingDir = layout.buildDirectory.dir("central-portal-staging")
val mavenCentralSigningPassword = providers.gradleProperty("signingInMemoryKeyPassword")
    .orElse(providers.environmentVariable("SIGNING_PASSWORD"))

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
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // PlaceholderAPI (optional, for /papi placeholders)
    compileOnly("me.clip:placeholderapi:2.12.2")
    // LuckPerms API (optional, for elected mayor username prefix)
    compileOnly("net.luckperms:api:5.5")

    // Runtime libraries are declared in plugin.yml for the slim Spigot upload jar.
    // Shadow can still build an optional -all jar for local/offline testing.
    implementation(kotlin("stdlib"))

    // Cloud v2
    // NOTE: Cloud v2 "standard" parsers live in cloud-core (transitive via cloud-paper),
    // so we DON'T need a separate cloud-parser-standard dependency.
    val cloudVersion = "2.0.0-beta.15"
    implementation(platform("org.incendo:cloud-minecraft-bom:$cloudVersion"))
    implementation("org.incendo:cloud-paper:$cloudVersion")

    // Audit log JSONL serialization/deserialization
    implementation("com.google.code.gson:gson:2.14.0")

    // SQLite (data store)
    implementation("org.xerial:sqlite-jdbc:3.53.1.0")

    // MySQL (data store)
    implementation("com.mysql:mysql-connector-j:9.7.0")
    implementation("com.zaxxer:HikariCP:7.0.2")

    // Coroutines (async IO + main-thread hop helpers)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("me.clip:placeholderapi:2.12.2")
    testImplementation("net.luckperms:api:5.5")
}

tasks.processResources {
    filteringCharset = "UTF-8"

    // Allow plugin.yml to reference ${version}
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

val apiJar = tasks.register<Jar>("apiJar") {
    group = "build"
    description = "Builds the MayorSystem addon API jar."
    archiveClassifier.set("api")
    from(sourceSets.main.get().output) {
        include("mayorSystem/api/**")
    }
}

val apiSourcesJar = tasks.register<Jar>("apiSourcesJar") {
    group = "build"
    description = "Builds the MayorSystem addon API sources jar."
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource) {
        include("mayorSystem/api/**")
    }
}

val apiJavadocJar = tasks.register<Jar>("apiJavadocJar") {
    group = "build"
    description = "Builds the MayorSystem addon API javadoc jar required by Maven Central."
    archiveClassifier.set("javadoc")
    from("docs/addons/api-reference.md") {
        into("docs")
    }
}

publishing {
    publications {
        create<MavenPublication>("mayorSystemApi") {
            groupId = "io.github.louguerrier22"
            artifactId = "mayorsystem-api"
            version = apiPublicationVersion

            artifact(apiJar) {
                classifier = null
            }
            artifact(apiSourcesJar)
            artifact(apiJavadocJar)

            pom {
                name.set("MayorSystem API")
                description.set("Public addon API for MayorSystem.")
                url.set("https://github.com/L1cked/MayorSystem")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/L1cked/MayorSystem/blob/main/LICENSE")
                    }
                }

                developers {
                    developer {
                        id.set("L1cked")
                        name.set("L1cked")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/L1cked/MayorSystem.git")
                    developerConnection.set("scm:git:ssh://git@github.com/L1cked/MayorSystem.git")
                    url.set("https://github.com/L1cked/MayorSystem")
                }
            }
        }
    }

    repositories {
        maven {
            name = "CentralPortalStaging"
            url = uri(centralPortalStagingDir)
        }
    }
}

val cleanCentralPortalStaging = tasks.register<Delete>("cleanCentralPortalStaging") {
    delete(centralPortalStagingDir)
}

tasks.named("publishMayorSystemApiPublicationToCentralPortalStagingRepository") {
    dependsOn(cleanCentralPortalStaging)
}

val signCentralPortalStaging = tasks.register<GpgSignCentralPortalStagingTask>("signCentralPortalStaging") {
    group = "publishing"
    description = "Signs the staged Maven Central API artifacts with command-line GPG."
    dependsOn("publishMayorSystemApiPublicationToCentralPortalStagingRepository")
    stagingDirectory.set(centralPortalStagingDir)
    signingPassword.set(
        mavenCentralSigningPassword.orElse(
            providers.provider {
                throw GradleException(
                    "Missing signing password. Set signingInMemoryKeyPassword or SIGNING_PASSWORD."
                )
            }
        )
    )
}

tasks.register<Zip>("centralPortalBundle") {
    group = "publishing"
    description = "Builds a signed Maven Central Portal upload bundle for the MayorSystem API."
    dependsOn(signCentralPortalStaging)
    archiveFileName.set("mayorsystem-api-$apiPublicationVersion-central-portal.zip")
    destinationDirectory.set(layout.buildDirectory.dir("central-portal"))
    from(centralPortalStagingDir)
}

tasks.withType<ShadowJar>().configureEach {
    // Optional bundled jar. The normal jar is the Spigot upload artifact.
    archiveClassifier.set("all")
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
    mergeServiceFiles()

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

    // Isolate libraries commonly shared by Paper or other plugins.
    relocate("com.google.gson", "mayorSystem.shaded.gson")
    relocate("com.zaxxer.hikari", "mayorSystem.shaded.hikari")
    // Xerial SQLite JDBC loads JNI symbols bound to org.sqlite.*; relocating it breaks native startup.
    relocate("com.mysql", "mayorSystem.shaded.mysql")
}

tasks.test {
    useJUnitPlatform()
}
