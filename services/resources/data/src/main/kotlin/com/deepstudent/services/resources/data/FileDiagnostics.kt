package com.deepstudent.services.resources.data

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException

internal enum class FileStage {
    ROOT, RESOLVE, DIRECTORY, TEMP_WRITE, PUBLISH, CLEANUP, READ, SOURCE_READ, OUTPUT, SPACE,
}

internal inline fun <T> fileOperation(stage: FileStage, action: () -> T): T = try {
    action()
} catch (failure: IOException) {
    fileFailure(stage, failure)
} catch (failure: SecurityException) {
    fileFailure(stage, failure)
}

internal fun fileFailure(stage: FileStage, failure: Exception): Nothing {
    val causeClass = when (failure) {
        is AccessDeniedException -> "AccessDeniedException"
        is FileAlreadyExistsException -> "FileAlreadyExistsException"
        is NoSuchFileException -> "NoSuchFileException"
        is AtomicMoveNotSupportedException -> "AtomicMoveNotSupportedException"
        is FileSystemException -> "FileSystemException"
        is IOException -> "IOException"
        is SecurityException -> "SecurityException"
        else -> "Other"
    }
    throw DstuException(DstuError.internal(
        "RESOURCE_STORAGE_FAILED", "resource storage failed",
        mapOf("stage" to stage.name, "causeClass" to causeClass),
    ))
}
