@file:Suppress("UnstableApiUsage")

import com.aliucord.gradle.AliucordExtension
import com.android.build.gradle.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

subprojects {
    apply {
        plugin("com.android.library")
        plugin("com.aliucord.plugin")
        plugin("org.jetbrains.kotlin.android")
        plugin("org.jlleitschuh.gradle.ktlint")
    }

    configure<LibraryExtension> {
        namespace = "com.github.kairusds"
        compileSdk = 36

        defaultConfig {
            minSdk = 21
        }

        buildFeatures {
            aidl = false
            buildConfig = true
            renderScript = false
            shaders = false
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }

    configure<AliucordExtension> {
        author("kairusds", 0L, hyperlink = true)
        github("https://github.com/kairusds/aliucord-plugins")
    }

    configure<KtlintExtension> {
        version.set("1.8.0")

        coloredOutput.set(true)
        outputColorName.set("RED")
        ignoreFailures.set(true)
    }

    configure<KotlinAndroidExtension> {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
            optIn.add("kotlin.RequiresOptIn")
        }
    }

    @Suppress("unused")
    dependencies {
        val compileOnly by configurations
        val implementation by configurations

        compileOnly("com.discord:discord:126021")
        compileOnly("com.aliucord:Aliucord:2.6.0")
        compileOnly("com.aliucord:Aliuhook:1.1.4")
        compileOnly("org.jetbrains.kotlin:kotlin-stdlib:1.5.21")
    }
}
