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
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.hdlee73.englishstudy.win.MainKt"
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe
            )
            packageName = "EngHub"
            packageVersion = "1.0.0"
            description = "EngHub Windows English Study Application"
            copyright = "© 2026 hdlee73. All rights reserved."
            vendor = "hdlee73"
        }
    }
}
