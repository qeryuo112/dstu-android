package com.deepstudent.buildlogic.convention

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Configures AGP and KGP through their public typed DSLs.
 *
 * <p>AGP 8.13.0 and Kotlin 2.2.21 are compile-time dependencies of build-logic.
 * The build fails at compile time when an upstream DSL changes instead of
 * silently falling back to reflection.</p>
 */
internal object AndroidConvention {
    fun applyAndroid(project: Project, application: Boolean) {
        DstuVersions.requireFixedGradle()
        project.pluginManager.apply(if (application) "com.android.application" else "com.android.library")
        project.pluginManager.apply("org.jetbrains.kotlin.android")
        configureAndroidExtension(project, application)
        configureJavaToolchain(project)
        configureKotlin(project)
        project.logger.lifecycle(
            "[dstu.convention] id=dstu.android.${if (application) "application" else "library"} " +
                "project=${project.path} compileSdk=${DstuVersions.COMPILE_SDK} " +
                "minSdk=${DstuVersions.MIN_SDK} targetSdk=${DstuVersions.TARGET_SDK} jdk=${DstuVersions.JDK}",
        )
    }

    fun applyKotlinLibrary(project: Project) {
        DstuVersions.requireFixedGradle()
        project.pluginManager.apply("java-library")
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")
        configureJavaToolchain(project)
        configureKotlin(project)
        project.logger.lifecycle(
            "[dstu.convention] id=dstu.kotlin.library project=${project.path} jdk=${DstuVersions.JDK}",
        )
    }

    @Suppress("DEPRECATION")
    private fun configureAndroidExtension(project: Project, application: Boolean) {
        if (application) {
            val android = project.extensions.getByType(ApplicationExtension::class.java)
            configureCommonAndroid(android, project, application)
            android.defaultConfig.targetSdk = DstuVersions.TARGET_SDK
            if (android.defaultConfig.applicationId == null) {
                android.defaultConfig.applicationId = "com.deepstudent.app"
            }
            android.defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        } else {
            val android = project.extensions.getByType(LibraryExtension::class.java)
            configureCommonAndroid(android, project, application)
            android.defaultConfig.targetSdk = DstuVersions.TARGET_SDK
        }
    }

    private fun configureCommonAndroid(android: CommonExtension<*, *, *, *, *, *>, project: Project, application: Boolean) {
        android.compileSdk = DstuVersions.COMPILE_SDK
        android.defaultConfig.minSdk = DstuVersions.MIN_SDK
        if (android.namespace == null) {
            android.namespace = defaultNamespace(project.path, application)
        }
        android.buildToolsVersion = DstuVersions.BUILD_TOOLS
        android.defaultConfig.ndk.abiFilters.add(DstuVersions.ABI)
        android.packaging.jniLibs.useLegacyPackaging = true
        android.compileOptions.sourceCompatibility = JavaVersion.VERSION_17
        android.compileOptions.targetCompatibility = JavaVersion.VERSION_17
    }

    private fun configureJavaToolchain(project: Project) {
        project.extensions.findByType(JavaPluginExtension::class.java)?.toolchain?.languageVersion?.set(
            JavaLanguageVersion.of(DstuVersions.JDK),
        )
    }

    private fun configureKotlin(project: Project) {
        val kotlin = project.extensions.getByType(KotlinProjectExtension::class.java)
        kotlin.jvmToolchain(DstuVersions.JDK)
        when (kotlin) {
            is KotlinAndroidProjectExtension -> kotlin.compilerOptions.jvmTarget.set(JvmTarget.JVM_17)

            is KotlinJvmProjectExtension -> kotlin.compilerOptions.jvmTarget.set(JvmTarget.JVM_17)

            else -> throw GradleException(
                "DSTU_CONVENTION_KOTLIN_EXTENSION_UNSUPPORTED project=${project.path} " +
                    "type=${kotlin.javaClass.name}",
            )
        }
        project.tasks.withType(KotlinCompile::class.java).configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    private fun defaultNamespace(path: String, application: Boolean): String {
        if (application) {
            return "com.deepstudent.app"
        }
        val segments = path.split(':').filter { it.isNotBlank() }
        return (listOf("com", "deepstudent") + segments)
            .joinToString(".") { it.replace('-', '_') }
    }
}
