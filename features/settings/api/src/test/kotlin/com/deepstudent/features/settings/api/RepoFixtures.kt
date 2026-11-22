package com.deepstudent.features.settings.api

import com.deepstudent.core.common.json.JsonValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object RepoFixtures {
    fun path(relative: String): Path {
        var directory: Path? = Paths.get("").toAbsolutePath()
        while (directory != null) {
            val candidate = directory.resolve(relative)
            if (Files.exists(candidate)) return candidate
            directory = directory.parent
        }
        error("fixture not found: $relative")
    }

    fun json(relative: String): JsonValue.Object =
        JsonValue.parseObject(Files.readString(path(relative)), relative)
}
