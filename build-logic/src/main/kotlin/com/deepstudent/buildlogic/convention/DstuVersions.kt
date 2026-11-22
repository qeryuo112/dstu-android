package com.deepstudent.buildlogic.convention

import org.gradle.api.GradleException
import org.gradle.util.GradleVersion

internal object DstuVersions {
    const val GRADLE = "8.14.2"
    const val AGP = "8.13.0"
    const val KOTLIN = "2.2.21"
    const val JDK = 17
    const val DETEKT = "1.23.8"
    const val COMPILE_SDK = 36
    const val MIN_SDK = 26
    const val TARGET_SDK = 36
    const val GSON = "2.11.0"
    const val BUILD_TOOLS = "36.0.0"
    const val ABI = "arm64-v8a"

    fun requireFixedGradle() {
        val actual = GradleVersion.current().version
        if (actual != GRADLE) {
            throw GradleException(
                "DSTU_TOOLCHAIN_GRADLE_MISMATCH expected=$GRADLE actual=$actual " +
                    "reason=S1.01 fixes Gradle for build-logic and architecture tasks"
            )
        }
    }
}
