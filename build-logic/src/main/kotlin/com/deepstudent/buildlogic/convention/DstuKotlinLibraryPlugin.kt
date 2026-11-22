package com.deepstudent.buildlogic.convention

import org.gradle.api.Plugin
import org.gradle.api.Project

class DstuKotlinLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        AndroidConvention.applyKotlinLibrary(project)
    }
}
