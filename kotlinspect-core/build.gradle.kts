import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.mavenPublish)
}

group = "io.github.asterx5"
version = providers.gradleProperty("kotlinspect.version").get()

// The library version as a constant, for the "creator" of exported HAR files.
val generateBuildInfo = tasks.register("generateKotlinspectBuildInfo") {
    val outputDir = layout.buildDirectory.dir("generated/kotlinspect-buildinfo/kotlin")
    val libraryVersion = version.toString()
    inputs.property("version", libraryVersion)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("io/github/asterx5/kotlinspect/internal/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package io.github.asterx5.kotlinspect.internal\n\n" +
                "internal object BuildInfo {\n    const val VERSION: String = \"$libraryVersion\"\n}\n",
        )
    }
}

kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // The internal package holds the UI-module SPI (@InternalKotlinspectApi); it is not API.
        filters {
            exclude {
                byNames.add("io.github.asterx5.kotlinspect.internal.**")
            }
        }
    }

    // The XCFramework for the Swift package: the same two iOS targets that are published, static,
    // with Kotlog exported so Swift can reach it. Building it needs macOS (CI only); adding the
    // binaries does not change the published klibs.
    val xcf = XCFramework("KotlinspectCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "KotlinspectCore"
            isStatic = true
            binaryOption("bundleId", "io.github.asterx5.kotlinspect.core")
            export(project(":kotlog"))
            xcf.add(this)
        }
    }

    // jvmAndAndroidMain holds the OkHttp interceptor: code shared by the JVM and Android only.
    // Extending the default template keeps iosMain, appleMain and the rest as they were.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    // persistentMain holds the Room database: every platform with storage (all but web).
    applyDefaultHierarchyTemplate {
        common {
            group("persistent") {
                group("jvmAndAndroid") {
                    withJvm()
                    withCompilations { it.target.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm }
                }
                group("ios") { withIos() }
            }
        }
    }

    jvm()

    android {
        namespace = "io.github.asterx5.kotlinspect.core"
        compileSdk = libs.versions.ks.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        withHostTest {}

        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.file("consumer-rules.pro")
        }
    }

    // Built with the current compiler, but readable by apps on Kotlin 2.1.20 and newer, and
    // depending on the 2.1.20 standard library rather than forcing the newest one.
    coreLibrariesVersion = libs.versions.ks.kotlinStdlib.get()

    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        optIn.add("io.github.asterx5.kotlinspect.InternalKotlinspectApi")
        optIn.add("io.github.asterx5.kotlog.InternalKotlogApi")
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildInfo)
        }
        commonMain.dependencies {
            api(libs.ks.ktor.client.core)
            api(project(":kotlog"))
            implementation(libs.ks.coroutines.core)
            implementation(libs.ks.serialization.json)
            implementation(libs.ks.room.runtime)
        }
        // OkHttp is the app's own dependency: compileOnly keeps it out of the published POM and
        // module metadata, so apps without OkHttp never download it.
        getByName("jvmAndAndroidMain").dependencies {
            compileOnly(libs.ks.okhttp)
        }
        androidMain.dependencies {
            implementation(libs.ks.sqlite.framework)
        }
        jvmMain.dependencies {
            implementation(libs.ks.sqlite.bundled)
        }
        iosMain.dependencies {
            implementation(libs.ks.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ks.coroutines.test)
            implementation(libs.ks.ktor.client.mock)
        }
        jvmTest.dependencies {
            implementation(libs.ks.sqlite.bundled)
            // -PokhttpTestVersion=4.9.3 / 4.12.1 / 5.5.1 runs the OkHttp tests against each line.
            val okhttpTestVersion = providers.gradleProperty("okhttpTestVersion").getOrElse("4.12.1")
            implementation("com.squareup.okhttp3:okhttp:$okhttpTestVersion")
            implementation("com.squareup.okhttp3:mockwebserver:$okhttpTestVersion")
            implementation(libs.ks.ktor.client.okhttp)
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// The OkHttp tests run against the version picked with -PokhttpTestVersion, even where another
// test dependency (Ktor's OkHttp engine) asks for a newer one.
val okhttpTestVersion = providers.gradleProperty("okhttpTestVersion").getOrElse("4.12.1")
configurations.matching { it.name.startsWith("jvmTest") }.configureEach {
    resolutionStrategy.force("com.squareup.okhttp3:okhttp:$okhttpTestVersion", "com.squareup.okhttp3:mockwebserver:$okhttpTestVersion")
}

dependencies {
    add("kspAndroid", libs.ks.room.compiler)
    add("kspJvm", libs.ks.room.compiler)
    add("kspIosArm64", libs.ks.room.compiler)
    add("kspIosSimulatorArm64", libs.ks.room.compiler)
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates("io.github.asterx5", "kotlinspect-core", version.toString())
    pom {
        name.set("Kotlinspect Core")
        description.set("Kotlinspect without Compose: Ktor capture, storage, sessions and the native iOS UI.")
        url.set("https://github.com/asterx5/kotlinspect")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("asterx5")
                name.set("asterx5")
                url.set("https://github.com/asterx5")
            }
        }
        scm {
            url.set("https://github.com/asterx5/kotlinspect")
            connection.set("scm:git:git://github.com/asterx5/kotlinspect.git")
            developerConnection.set("scm:git:ssh://git@github.com/asterx5/kotlinspect.git")
        }
    }
}
