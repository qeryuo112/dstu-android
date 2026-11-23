package com.deepstudent.app.backup

import com.deepstudent.platform.database.MigrationSource
import java.io.File

internal fun hostRestoreFixture(root: File): RestoreSqlFixture {
    val repository = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { it.resolve("config/schema/final-lock.json").isFile }
    return RestoreSqlFixture(root, MigrationSource { repository.resolve(it).readBytes() }) {
        repository.resolve("services/backup/backend/src/test/resources/desktop-v3-full.json").readText()
    }
}
