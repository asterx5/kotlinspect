import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.mavenPublish)
}

group = "io.github.asterx5"
version = providers.gradleProperty("kotlinspect.version").get()

// Kotlog: a tiny logging API with no dependencies, so any module (data, domain...) can use it.
// It prints the platform way and hands entries to Kotlinspect when that is in the app.
kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    iosArm64()
    iosSimulatorArm64()

    jvm()

    // Web: compile-compatibility targets. Everything compiles and runs, and does nothing.
    js(IR) {
        browser { testTask { enabled = false } }
        nodejs()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser { testTask { enabled = false } }
        nodejs()
    }

    android {
        namespace = "io.github.asterx5.kotlog"
        compileSdk = libs.versions.ks.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    coreLibrariesVersion = libs.versions.ks.kotlinStdlib.get()

    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("io.github.asterx5.kotlog.InternalKotlogApi")
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates("io.github.asterx5", "kotlog", version.toString())
    pom {
        name.set("Kotlog")
        description.set("Tiny Kotlin Multiplatform logging API with tag, level, message and layer; shown in Kotlinspect's inspector.")
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
