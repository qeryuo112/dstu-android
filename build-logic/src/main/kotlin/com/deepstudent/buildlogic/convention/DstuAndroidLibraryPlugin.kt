package com.deepstudent.buildlogic.convention

import org.gradle.api.Plugin
import org.gradle.api.Project

class DstuAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        AndroidConvention.applyAndroid(project, application = false)
    }
}
