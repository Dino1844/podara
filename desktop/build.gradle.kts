import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.testing.Test
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.kotlin.serialization)
}

kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)

                implementation(libs.kotlinx.coroutines.core)

                // Network (Ktor + serialization)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.okhttp)

                // RSS parser
                implementation(libs.rssparser)

                // JDBC SQLite for desktop
                implementation("org.xerial:sqlite-jdbc:3.46.1.0")

                // JNA for libmpv binding
                implementation(files("../libs/jna-5.6.0.jar"))
                implementation(files("../libs/jna-platform-5.6.0.jar"))

                // Image loading
                implementation(libs.coil.compose)
                implementation(libs.coil.network.okhttp)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.compose.ui:ui-test:1.9.0")
                implementation("org.jetbrains.compose.ui:ui-test-junit4:1.9.0")
                implementation(libs.rssparser)
                // MockEngine for offline HTTP fakes (PodcastManagerTest lookup stub)
                implementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
}

compose.desktop {
    application {
        mainClass = "app.podara.MainKt"

        jvmArgs += "-Dfile.encoding=UTF-8"
        jvmArgs += "--add-modules=java.sql"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Podara"
            packageVersion = "1.0.3"
            description = "Podara"
            vendor = "Podara"

            windows {
                menuGroup = "Podara"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                dirChooser = true
                shortcut = true
                menu = true
                iconFile.set(project.file("../libs/icon.ico"))
            }
        }
    }
}

tasks.matching { it.name == "createRuntimeImage" }.configureEach {
    doLast {
        try {
            val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
            val jmodsPath = "$javaHome/jmods"

            // Find the runtime output directory
            val runtimeDir = File(project.buildDir, "compose/tmp/main/runtime")

            println("Patching JRE at ${runtimeDir.absolutePath} with the required module set")

            // An explicit module list instead of ALL-MODULE-PATH.
            //
            // The original `ALL-MODULE-PATH,java.sql` was a shortcut to work
            // around java.sql being missing, but it links in the entire JDK —
            // jshell, javadoc, jconsole, hotspot.agent, the compiler, jlink
            // itself — for a ~155 MB runtime and a ~180 MB installer. This list
            // is what the app actually touches: Compose/Skiko (desktop, xml,
            // logging), SQLite via JDBC (sql), Ktor/OkHttp (net.http, naming),
            // TLS (crypto.ec, crypto.provider), and JNA (unsupported).
            val modules = listOf(
                "java.base",
                "java.desktop",
                "java.logging",
                "java.management",
                "java.naming",
                "java.prefs",
                "java.scripting",
                "java.security.jgss",
                "java.security.sasl",
                "java.sql",
                "java.transaction.xa",
                "java.xml",
                "jdk.crypto.ec",
                "jdk.crypto.cryptoki",
                "jdk.crypto.mscapi",
                "jdk.localedata",
                "jdk.unsupported",
                "jdk.zipfs",
            ).joinToString(",")

            val tempDir = File(runtimeDir.parentFile, "runtime_with_sql")
            if (tempDir.exists()) tempDir.deleteRecursively()

            val process = ProcessBuilder(
                File(javaHome, "bin/jlink").absolutePath,
                "--module-path", jmodsPath,
                "--add-modules", modules,
                "--output", tempDir.absolutePath,
                "--no-header-files",
                "--no-man-pages",
                "--strip-debug",
                // Drop every locale except the ones the app ships strings for.
                "--include-locales=en,zh"
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0 && tempDir.exists()) {
                runtimeDir.deleteRecursively()
                if (tempDir.renameTo(runtimeDir)) {
                    val sizeMb = runtimeDir.walkTopDown().filter { it.isFile }
                        .sumOf { it.length() } / (1024.0 * 1024.0)
                    println("JRE patched successfully: %.1f MB".format(sizeMb))
                } else {
                    println("Warning: could not move the patched runtime into place")
                }
            } else {
                println("jlink warning (exit code $exitCode): $output")
            }
        } catch (e: Exception) {
            println("Warning: Could not patch JRE with java.sql: ${e.message}")
        }
    }
}
