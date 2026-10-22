import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.mavenPublish)
}

group = "io.github.asterx5"
version = providers.gradleProperty("kotlinspect.version").get()

kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Compose generates public synthetic classes for internal UI; they are not API.
        filters {
            exclude {
                byNames.add("io.github.asterx5.kotlinspect.internal.**")
            }
        }
    }

    iosArm64()
    iosSimulatorArm64()

    jvm()

    android {
        namespace = "io.github.asterx5.kotlinspect.lib"
        compileSdk = libs.versions.ks.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        withHostTest {}
    }

    // Built with the current compiler, but readable by apps on Kotlin 2.1.20 and newer, and
    // depending on the 2.1.20 standard library rather than forcing the newest one.
    coreLibrariesVersion = libs.versions.ks.kotlinStdlib.get()

    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("io.github.asterx5.kotlinspect.InternalKotlinspectApi")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kotlinspect-core"))
            implementation(libs.ks.coroutines.core)
            implementation(libs.ks.compose.runtime)
            implementation(libs.ks.compose.foundation)
            implementation(libs.ks.compose.ui)
        }
        androidMain.dependencies {
            implementation(libs.ks.activity.compose)
        }
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
    coordinates("io.github.asterx5", "kotlinspect", version.toString())
    pom {
        name.set("Kotlinspect")
        description.set("Kotlin Multiplatform network inspector for Ktor on Android, iOS and Desktop.")
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
