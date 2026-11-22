package com.deepstudent.buildlogic.quality

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.math.BigDecimal

/**
 * JVM-only JaCoCo report and non-zero coverage gate.
 *
 * <p>The plugin deliberately waits for the Java plugin. Android modules are not
 * silently included: their unit-test execution and generated classes need a
 * separate, explicit coverage design.</p>
 */
abstract class DstuCoverageExtension {
    abstract val minimum: Property<BigDecimal>
}

class DstuCoveragePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project.extensions.findByType(DstuCoverageExtension::class.java) != null) {
            return
        }
        project.pluginManager.apply("jacoco")
        project.extensions.configure(JacocoPluginExtension::class.java) { toolVersion = "0.8.13" }
        val extension = project.extensions.create("dstuCoverage", DstuCoverageExtension::class.java)
        extension.minimum.convention(BigDecimal("0.60"))
        project.pluginManager.withPlugin("java") {
            configureJvmProject(project, extension)
        }
        project.logger.lifecycle(
            "[dstu.coverage] plugin=applied project=${project.path} scope=java-unit-tests " +
                "report=build/reports/jacoco/test/html/index.html",
        )
    }

    private fun configureJvmProject(project: Project, extension: DstuCoverageExtension) {
        val java = project.extensions.getByType(JavaPluginExtension::class.java)
        val main = java.sourceSets.getByName("main")
        val test = project.tasks.named("test", Test::class.java)
        val coverageExecutionData = test.map {
            requireNotNull(it.extensions.getByType(JacocoTaskExtension::class.java).destinationFile) {
                "DSTU_COVERAGE_EXECUTION_DATA_MISSING project=${project.path}"
            }
        }
        val requireCoverageData = project.tasks.register("requireCoverageData") {
            dependsOn(test)
            doLast {
                val executionData = coverageExecutionData.get()
                check(!test.get().state.noSource && executionData.isFile && executionData.length() > 0) {
                    "DSTU_COVERAGE_NO_TEST_DATA project=${project.path}: run real unit tests before checking coverage"
                }
            }
        }
        val coverageClasses = main.output.asFileTree.matching {
            exclude(
                "**/R.class",
                "**/R\$*.class",
                "**/BuildConfig.class",
                "**/Manifest.class",
                "**/Manifest\$*.class",
            )
        }
        val report = project.tasks.named("jacocoTestReport", JacocoReport::class.java) {
            group = "verification"
            description = "Generates the real JaCoCo HTML/XML report for JVM unit tests."
            dependsOn(requireCoverageData)
            classDirectories.setFrom(coverageClasses)
            sourceDirectories.setFrom(main.allSource.srcDirs)
            executionData.setFrom(coverageExecutionData)
            reports {
                xml.required.set(true)
                html.required.set(true)
                csv.required.set(false)
            }
        }
        val verification = project.tasks.named(
            "jacocoTestCoverageVerification",
            JacocoCoverageVerification::class.java,
        ) {
            group = "verification"
            description = "Fails when JVM line coverage is below the configured minimum."
            dependsOn(requireCoverageData)
            classDirectories.setFrom(coverageClasses)
            sourceDirectories.setFrom(main.allSource.srcDirs)
            executionData.setFrom(coverageExecutionData)
        }
        configureThreshold(project, extension, report, verification)
        project.tasks.named("check") {
            dependsOn(verification)
        }
    }

    private fun configureThreshold(
        project: Project,
        extension: DstuCoverageExtension,
        report: TaskProvider<JacocoReport>,
        verification: TaskProvider<JacocoCoverageVerification>,
    ) {
        project.afterEvaluate {
            val minimum = extension.minimum.get()
            if (minimum <= BigDecimal.ZERO || minimum > BigDecimal.ONE) {
                throw GradleException(
                    "DSTU_COVERAGE_MINIMUM_INVALID project=${project.path} minimum=$minimum " +
                        "reason=expected (0.0,1.0]",
                )
            }
            verification.configure {
                violationRules {
                    rule {
                        limit {
                            counter = "LINE"
                            value = "COVEREDRATIO"
                            this.minimum = minimum
                        }
                    }
                }
            }
            project.logger.lifecycle(
                "[dstu.coverage] project=${project.path} minimum=$minimum " +
                    "report=${report.get().reports.html.outputLocation.get().asFile.absolutePath}",
            )
        }
    }
}
