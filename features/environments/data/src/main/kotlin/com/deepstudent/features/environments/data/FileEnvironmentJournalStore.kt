package com.deepstudent.features.environments.data

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.backend.EnvironmentJournal
import com.deepstudent.features.environments.backend.EnvironmentJournalCodec
import com.deepstudent.features.environments.backend.EnvironmentJournalStore
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal class FileEnvironmentJournalStore(
    private val file: File,
) : EnvironmentJournalStore {
    override fun load(): EnvironmentJournal? {
        if (!file.isFile) return null
        return try {
            EnvironmentJournalCodec.decode(file.readText(Charsets.UTF_8))
        } catch (failure: Exception) {
            throw EnvironmentCommandException(
                EnvironmentFailure(
                    EnvironmentFailureCode.CORRUPT_STATE,
                    "environment journal is corrupt",
                    recoverable = false,
                ),
                failure,
            )
        }
    }

    override fun save(journal: EnvironmentJournal) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.part")
        try {
            Files.newOutputStream(
                temporary.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { output ->
                output.write(EnvironmentJournalCodec.encode(journal).toByteArray(Charsets.UTF_8))
                output.flush()
            }
            FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (failure: Exception) {
            Files.deleteIfExists(temporary.toPath())
            throw DstuException(
                com.deepstudent.core.common.error.DstuError.internal(
                    "ENVIRONMENT_JOURNAL_WRITE_FAILED",
                    "environment journal could not be committed",
                ),
                failure,
            )
        }
    }
}
