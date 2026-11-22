package com.deepstudent.buildlogic.convention

import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.jvm.toolchain.JavaLanguageVersion

/**
 * Configures AGP and Kotlin through public Gradle APIs plus narrow reflection.
 *
 * <p>AGP/KGP are runtime-only dependencies of build-logic. Reflection keeps the
 * convention plugin compilable with Gradle's embedded Kotlin and prevents a
 * build-logic compile-time dependency on a newer Kotlin metadata version.</p>
 */
internal object AndroidConvention {
    fun applyAndroid(project: Project, application: Boolean) {
        DstuVersions.requireFixedGradle()
        project.pluginManager.apply(if (application) "com.android.application" else "com.android.library")
        project.pluginManager.apply("org.jetbrains.kotlin.android")
        configureAndroidExtension(project, application)
        configureJavaToolchain(project)
        configureKotlinToolchain(project)
        configureKotlinTasks(project)
        project.logger.lifecycle(
            "[dstu.convention] id=dstu.android.${if (application) "application" else "library"} " +
                "project=${project.path} compileSdk=${DstuVersions.COMPILE_SDK} " +
                "minSdk=${DstuVersions.MIN_SDK} targetSdk=${DstuVersions.TARGET_SDK} jdk=${DstuVersions.JDK}"
        )
    }

    fun applyKotlinLibrary(project: Project) {
        DstuVersions.requireFixedGradle()
        project.pluginManager.apply("java-library")
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")
        configureJavaToolchain(project)
        configureKotlinToolchain(project)
        configureKotlinTasks(project)
        project.logger.lifecycle(
            "[dstu.convention] id=dstu.kotlin.library project=${project.path} jdk=${DstuVersions.JDK}"
        )
    }

    private fun configureAndroidExtension(project: Project, application: Boolean) {
        val android = project.extensions.findByName("android")
            ?: throw GradleException("DSTU_CONVENTION_ANDROID_EXTENSION_MISSING project=${project.path}")
        setCompileSdk(android, DstuVersions.COMPILE_SDK)
        val defaultConfig = invokeGetter(android, "getDefaultConfig")
            ?: throw GradleException("DSTU_CONVENTION_DEFAULT_CONFIG_MISSING project=${project.path}")
        invokeIntSetter(defaultConfig, "setMinSdk", DstuVersions.MIN_SDK, project)
        invokeIntSetter(defaultConfig, "setTargetSdk", DstuVersions.TARGET_SDK, project)
        if (application && invokeGetter(defaultConfig, "getApplicationId") == null) {
            invokeStringSetter(defaultConfig, "setApplicationId", "com.deepstudent.app", project)
        }
        if (application) {
            invokeStringSetter(
                defaultConfig,
                "setTestInstrumentationRunner",
                "androidx.test.runner.AndroidJUnitRunner",
                project
            )
        }
        if (invokeGetter(android, "getNamespace") == null) {
            invokeStringSetter(android, "setNamespace", defaultNamespace(project.path, application), project)
        }
        invokeStringSetter(android, "setBuildToolsVersion", DstuVersions.BUILD_TOOLS, project)
        configureAbiFilters(defaultConfig, project)
        configurePackaging(android, project)
        val compileOptions = invokeGetter(android, "getCompileOptions")
        if (compileOptions != null) {
            setJavaVersion(compileOptions, "setSourceCompatibility")
            setJavaVersion(compileOptions, "setTargetCompatibility")
        }
    }

    private fun configureAbiFilters(defaultConfig: Any, project: Project) {
        val ndk = invokeGetter(defaultConfig, "getNdk")
            ?: throw GradleException("DSTU_CONVENTION_NDK_MISSING project=${project.path}")
        val filters = invokeGetter(ndk, "getAbiFilters") as? MutableSet<*>
        if (filters == null) {
            val setter = ndk.javaClass.methods.firstOrNull {
                it.name == "setAbiFilters" && it.parameterTypes.size == 1 &&
                    Collection::class.java.isAssignableFrom(it.parameterTypes[0])
            } ?: throw GradleException("DSTU_CONVENTION_ABI_FILTERS_MISSING project=${project.path}")
            setter.invoke(ndk, listOf(DstuVersions.ABI))
            return
        }
        @Suppress("UNCHECKED_CAST")
        (filters as MutableSet<String>).add(DstuVersions.ABI)
    }

    private fun configurePackaging(android: Any, project: Project) {
        val packaging = invokeGetter(android, "getPackagingOptions")
            ?: throw GradleException("DSTU_CONVENTION_PACKAGING_MISSING project=${project.path}")
        val jniLibs = invokeGetter(packaging, "getJniLibs")
            ?: throw GradleException("DSTU_CONVENTION_JNI_PACKAGING_MISSING project=${project.path}")
        val setter = jniLibs.javaClass.methods.firstOrNull {
            (it.name == "setUseLegacyPackaging" || it.name == "useLegacyPackaging") &&
                it.parameterTypes.size == 1 &&
                (it.parameterTypes[0] == Boolean::class.java ||
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType ||
                    it.parameterTypes[0] == Boolean::class.javaObjectType)
        } ?: throw GradleException(
            "DSTU_CONVENTION_LEGACY_PACKAGING_SETTER_MISSING project=${project.path}"
        )
        setter.invoke(jniLibs, true)
    }

    private fun setCompileSdk(android: Any, sdk: Int) {
        val methods = android.javaClass.methods
        val intMethod = methods.firstOrNull {
            (it.name == "compileSdkVersion" || it.name == "setCompileSdkVersion") &&
                it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (intMethod != null) {
            intMethod.invoke(android, sdk)
            return
        }
        val stringMethod = methods.firstOrNull {
            it.name == "setCompileSdkVersion" &&
                it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
        } ?: throw GradleException("DSTU_CONVENTION_COMPILE_SDK_SETTER_MISSING")
        stringMethod.invoke(android, sdk.toString())
    }

    private fun setJavaVersion(target: Any, methodName: String) {
        val method = target.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == JavaVersion::class.java
        } ?: return
        method.invoke(target, JavaVersion.VERSION_17)
    }

    private fun configureJavaToolchain(project: Project) {
        project.extensions.findByType(JavaPluginExtension::class.java)?.toolchain?.languageVersion?.set(
            JavaLanguageVersion.of(DstuVersions.JDK)
        )
    }

    private fun configureKotlinToolchain(project: Project) {
        val kotlinExtension = project.extensions.findByName("kotlin") ?: throw GradleException(
            "DSTU_CONVENTION_KOTLIN_EXTENSION_MISSING project=${project.path}"
        )
        val compilerOptions = invokeGetter(kotlinExtension, "getCompilerOptions")
        if (compilerOptions != null && setJvmTargetProperty(compilerOptions)) {
            return
        }
        val toolchainMethod = kotlinExtension.javaClass.methods.firstOrNull {
            it.name == "jvmToolchain" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: throw GradleException(
            "DSTU_CONVENTION_KOTLIN_TOOLCHAIN_FAILED project=${project.path} " +
                "reason=no compilerOptions.jvmTarget and no jvmToolchain(int)"
        )
        toolchainMethod.invoke(kotlinExtension, DstuVersions.JDK)
    }

    private fun configureKotlinTasks(project: Project) {
        project.tasks.configureEach {
            val className = javaClass.name
            if (className.endsWith("KotlinCompile") || className.endsWith("KotlinJvmCompile")) {
                setKotlinJvmTarget17(this, project)
            }
        }
    }

    private fun setKotlinJvmTarget17(task: Any, project: Project) {
        val compilerOptions = runCatching {
            task.javaClass.methods.firstOrNull { it.name == "getCompilerOptions" }?.invoke(task)
        }.getOrNull()
        if (compilerOptions != null && setJvmTargetProperty(compilerOptions)) {
            return
        }
        val kotlinOptions = runCatching {
            task.javaClass.methods.firstOrNull { it.name == "getKotlinOptions" }?.invoke(task)
        }.getOrNull() ?: return
        val setter = kotlinOptions.javaClass.methods.firstOrNull {
            it.name == "setJvmTarget" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java
        }
        if (setter == null) {
            throw GradleException(
                "DSTU_CONVENTION_KOTLIN_JVM_TARGET_FAILED project=${project.path} " +
                    "task=${task.javaClass.name} reason=no compilerOptions.jvmTarget or kotlinOptions.jvmTarget"
            )
        }
        setter.invoke(kotlinOptions, "17")
    }

    private fun setJvmTargetProperty(compilerOptions: Any): Boolean {
        val jvmTargetClass = runCatching {
            Class.forName("org.jetbrains.kotlin.gradle.dsl.JvmTarget")
        }.getOrNull() ?: return false
        val jvm17 = runCatching { jvmTargetClass.getField("JVM_17").get(null) }.getOrNull()
            ?: return false
        val targetProperty = runCatching {
            compilerOptions.javaClass.methods.firstOrNull { it.name == "getJvmTarget" }
                ?.invoke(compilerOptions) as? Property<*>
        }.getOrNull() ?: return false
        @Suppress("UNCHECKED_CAST")
        (targetProperty as Property<Any>).set(jvm17)
        return true
    }

    private fun defaultNamespace(path: String, application: Boolean): String {
        if (application) {
            return "com.deepstudent.app"
        }
        val segments = path.split(':').filter { it.isNotBlank() }
        return (listOf("com", "deepstudent") + segments)
            .joinToString(".") { it.replace('-', '_') }
    }

    private fun invokeGetter(target: Any, methodName: String): Any? {
        val method = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
            ?: return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun invokeIntSetter(target: Any, methodName: String, value: Int, project: Project) {
        val method = target.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == 1 &&
                (it.parameterTypes[0] == Int::class.javaPrimitiveType ||
                    it.parameterTypes[0] == Int::class.javaObjectType)
        } ?: throw GradleException("DSTU_CONVENTION_SETTER_MISSING project=${project.path} method=$methodName")
        method.invoke(target, value)
    }

    private fun invokeStringSetter(target: Any, methodName: String, value: String, project: Project) {
        val method = target.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java
        } ?: throw GradleException("DSTU_CONVENTION_SETTER_MISSING project=${project.path} method=$methodName")
        method.invoke(target, value)
    }
}
