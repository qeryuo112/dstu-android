plugins {
    id("dstu.android.library")
}

android {
    namespace = "com.deepstudent.lib"
}

tasks.register("printDstuConvention") {
    doLast {
        val kotlinExtension = project.extensions.getByType(
            org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension::class.java,
        )
        println("DstuCompileSdk=${android.compileSdk}")
        println("DstuMinSdk=${android.defaultConfig.minSdk}")
        println("DstuTargetSdk=${android.defaultConfig.targetSdk}")
        println("DstuAbi=${android.defaultConfig.ndk.abiFilters.joinToString(",")}")
        println("DstuLegacyPackaging=${android.packaging.jniLibs.useLegacyPackaging}")
        println("DstuKotlinJvmTarget=${kotlinExtension.compilerOptions.jvmTarget.get().target}")
    }
}
