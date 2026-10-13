plugins {
    kotlin("jvm") version "2.1.10"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10"
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.1")
    implementation("javazoom:jlayer:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("org.json:json:20240303")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.apache.pdfbox:pdfbox:2.0.32")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

// One place for the app version; also written to app.properties, which the app reads for the info dialog and the update check.
val appVersion = "1.32.0"

tasks.processResources {
    inputs.property("appVersion", appVersion)
    filesMatching("app.properties") { expand(mapOf("appVersion" to appVersion)) }
}

compose.desktop {
    application {
        mainClass = "com.hdlee73.englishstudy.MainKt"
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe
            )
            includeAllModules = true
            packageName = "EngHub"
            packageVersion = appVersion
            description = "EngHub Windows English Study Application"
            copyright = "© 2026 hdlee73. All rights reserved."
            vendor = "hdlee73"
            windows {
                iconFile.set(project.file("packaging/icon.ico"))
                menuGroup = "EngHub"
                shortcut = true
                menu = true
                dirChooser = true
                perUserInstall = true
                // A fixed id lets a newer installer replace the installed version (auto-update).
                upgradeUuid = "6f1f4b6e-3b0d-4a49-9a7e-2f0d7c6a1e11"
            }
        }
    }
}
