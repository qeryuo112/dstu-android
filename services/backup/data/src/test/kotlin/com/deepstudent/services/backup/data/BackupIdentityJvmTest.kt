package com.deepstudent.services.backup.data

import com.deepstudent.platform.database.MigrationSource
import java.io.File
import java.nio.file.Files

class BackupIdentityJvmTest : BackupIdentityContractTest() {
    override val root: File = Files.createTempDirectory("backup-identity-").toFile()
    private val repository = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { it.resolve("config/schema/final-lock.json").isFile }
    override val migrations = MigrationSource { repository.resolve(it).readBytes() }
    override fun fixture(name: String) = repository.resolve("fixtures/legacy-mobile/$name").readBytes()
}
