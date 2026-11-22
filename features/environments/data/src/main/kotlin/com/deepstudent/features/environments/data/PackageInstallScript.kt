package com.deepstudent.features.environments.data

import com.deepstudent.features.environments.api.PackageKind
import com.deepstudent.features.environments.backend.PackageRecipe
import java.util.UUID

/** Recipes are validated domain values; scripts never accept a UI-supplied command. */
internal object PackageInstallScript {
    fun render(recipe: PackageRecipe, generation: String = UUID.randomUUID().toString()): String = buildString {
        appendLine("set -eu")
        appendLine("export DEBIAN_FRONTEND=noninteractive")
        appendLine("apt-get -o APT::Sandbox::User=root -o Acquire::Languages=none -o Acquire::http::Timeout=30 -o APT::Update::Error-Mode=any update")
        appendLine("dpkg --configure -a")
        appendLine("apt-get -o APT::Sandbox::User=root install -y --no-install-recommends ${recipe.aptPackages.joinToString(" ")}")
        if (recipe.kind == PackageKind.VENV_PIP) appendVenv(recipe, generation)
        recipe.aptPackages.forEach { name ->
            appendLine("version=\"\$(dpkg-query -W -f='\${Version}' '$name')\"")
            appendLine("printf 'DSTU_VERSION\\t$name\\t%s\\n' \"\$version\"")
        }
        if (recipe.kind == PackageKind.VENV_PIP) appendPythonVersions(recipe)
        appendLine("printf 'DSTU_PACKAGE_OK\\n'")
    }

    private fun StringBuilder.appendVenv(recipe: PackageRecipe, generation: String) {
        require(generation.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        val path = requireNotNull(recipe.venvPath)
        val directory = quote("$path.generations/$generation")
        val packages = recipe.pipRequirements.joinToString(" ") { quote("${it.name}==${it.version}") }
        appendLine("pointer=${quote(path)}")
        appendLine("generation=$directory")
        appendLine("pending=\"\${pointer}.next\"")
        appendLine("test ! -e \"\$pointer\" || test -L \"\$pointer\"")
        appendLine("mkdir -p \"\$(dirname \"\$generation\")\"")
        appendLine("committed=0")
        appendLine("trap 'rm -f \"\$pending\"; if [ \"\$committed\" = 0 ]; then rm -rf \"\$generation\"; fi' EXIT")
        // A venv cannot be renamed: console-script shebangs contain its original absolute path.
        // Build in an immutable generation and atomically switch only the public symlink.
        appendLine("python3 -m venv \"\$generation\"")
        appendLine("\"\$generation/bin/python\" -m pip install --disable-pip-version-check --no-cache-dir $packages")
        appendLine("rm -f \"\$pending\"")
        appendLine("ln -s \"\$generation\" \"\$pending\"")
        appendLine("mv -Tf \"\$pending\" \"\$pointer\"")
        appendLine("committed=1")
        appendLine("\"\$pointer/bin/pip\" --version")
    }

    private fun StringBuilder.appendPythonVersions(recipe: PackageRecipe) {
        val venv = quote(requireNotNull(recipe.venvPath))
        appendLine("printf 'DSTU_VERSION\\tpython3\\t%s\\n' \"\$(python3 -c 'import platform; print(platform.python_version())')\"")
        recipe.pipRequirements.forEach { requirement ->
            val expression = "from importlib.metadata import version; v=version('${requirement.name}'); assert v=='${requirement.version}'; print(v)"
            // Separate assignment keeps a failed probe fatal; printf alone masks substitution failures.
            appendLine("version=\"\$($venv/bin/python -c ${quote(expression)})\"")
            appendLine("printf 'DSTU_VERSION\\t${requirement.name}\\t%s\\n' \"\$version\"")
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
