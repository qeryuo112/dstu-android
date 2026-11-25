package com.deepstudent.services.sync.data.s230

import com.deepstudent.platform.database.MigrationSource
import java.io.File

internal fun s230HostMigrationSource(): MigrationSource {
    val repository = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "config/schema/final-lock.json").isFile }
    return MigrationSource { repository.resolve(it).readBytes() }
}
