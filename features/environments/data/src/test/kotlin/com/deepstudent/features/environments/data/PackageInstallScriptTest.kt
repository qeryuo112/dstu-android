package com.deepstudent.features.environments.data

import com.deepstudent.features.environments.backend.PackageRecipeCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageInstallScriptTest {
    @Test fun aptMustFailOnBadIndexesAndInterruptedConfiguration() {
        val script = PackageInstallScript.render(PackageRecipeCatalog.Git)
        assertTrue(script.contains("APT::Update::Error-Mode=any"))
        assertTrue(script.contains("Acquire::Languages=none"))
        assertFalse(script.contains("|| true"))
    }

    @Test fun venvBuildUsesStablePathAndSwitchesOnlyItsPointer() {
        val script = PackageInstallScript.render(PackageRecipeCatalog.ProjectVenv, "test-generation")
        assertTrue(script.contains(".venv.generations/test-generation"))
        assertTrue(script.contains("mv -Tf \"\$pending\" \"\$pointer\""))
        assertTrue(script.contains("\"\$pointer/bin/pip\" --version"))
        assertTrue(script.contains("from importlib.metadata import version"))
        assertFalse(script.contains("mv \"\$generation\""))
        assertFalse(script.contains(".venv.old"))
    }
}
