package com.deepstudent.features.environments.data

import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.RootfsManifest

internal enum class RootfsEntryType {
    DIRECTORY,
    REGULAR,
    SYMLINK,
    HARDLINK,
    SPECIAL,
}

internal data class RootfsEntryDescriptor(
    val name: String,
    val type: RootfsEntryType,
    val size: Long,
    val linkName: String? = null,
    val executable: Boolean = false,
)

internal sealed interface RootfsEntryAction {
    data class Directory(val relative: List<String>) : RootfsEntryAction

    data class Regular(val relative: List<String>, val size: Long, val executable: Boolean) :
        RootfsEntryAction

    data class Symlink(val relative: List<String>, val target: String) : RootfsEntryAction

    data class Hardlink(val relative: List<String>, val target: List<String>) : RootfsEntryAction

    data object Skip : RootfsEntryAction
}

/**
 * Stateful archive policy. Entries are accepted in tar order so a later member can never
 * traverse a symlink created by an earlier member.
 */
internal class RootfsExtractionPolicy(private val manifest: RootfsManifest) {
    private val directories = mutableSetOf<List<String>>()
    private val regularFiles = mutableSetOf<List<String>>()
    private val symlinks = mutableSetOf<List<String>>()
    private var entryCount = 0
    private var payloadBytes = 0L

    val entriesSeen: Int get() = entryCount

    fun accept(entry: RootfsEntryDescriptor): RootfsEntryAction {
        beginEntry()
        val member = normalizeMember(entry.name)
        if (entry.type == RootfsEntryType.SPECIAL) return RootfsEntryAction.Skip
        if (member.isEmpty()) return acceptRoot(entry)
        requireSafeParents(member)
        return when (entry.type) {
            RootfsEntryType.DIRECTORY -> acceptDirectory(member)
            RootfsEntryType.REGULAR -> acceptRegular(member, entry.size, entry.executable)
            RootfsEntryType.SYMLINK -> acceptSymlink(member, entry.linkName)
            RootfsEntryType.HARDLINK -> acceptHardlink(member, entry.linkName)
            RootfsEntryType.SPECIAL -> RootfsEntryAction.Skip
        }
    }

    private fun beginEntry() {
        entryCount++
        if (entryCount > manifest.entryCount) {
            fail(
                EnvironmentFailureCode.ENTRY_LIMIT,
                "rootfs archive exceeds the frozen entry count",
            )
        }
    }

    private fun acceptRoot(entry: RootfsEntryDescriptor): RootfsEntryAction {
        if (entry.type != RootfsEntryType.DIRECTORY) {
            fail(
                EnvironmentFailureCode.PATH_ESCAPE,
                "rootfs archive root must be a directory",
            )
        }
        return RootfsEntryAction.Directory(emptyList())
    }

    fun finish() {
        if (entryCount != manifest.entryCount) {
            fail(
                EnvironmentFailureCode.INVALID_ARCHIVE,
                "rootfs archive entry count does not match the frozen manifest",
            )
        }
        if (payloadBytes > manifest.extractionBytesBudget) {
            fail(
                EnvironmentFailureCode.ENTRY_LIMIT,
                "rootfs archive exceeds the extraction byte budget",
            )
        }
    }

    private fun acceptDirectory(member: List<String>): RootfsEntryAction {
        if (member in symlinks || member in regularFiles) {
            fail(EnvironmentFailureCode.LINK_ESCAPE, "directory duplicates a non-directory member")
        }
        directories += member
        return RootfsEntryAction.Directory(member)
    }

    private fun acceptRegular(
        member: List<String>,
        size: Long,
        executable: Boolean,
    ): RootfsEntryAction {
        if (size < 0 || size > MAX_FILE_BYTES) {
            fail(EnvironmentFailureCode.ENTRY_LIMIT, "rootfs file entry is too large")
        }
        if (member in directories || member in symlinks || member in regularFiles) {
            fail(EnvironmentFailureCode.PATH_ESCAPE, "duplicate rootfs file member")
        }
        payloadBytes = saturatingAdd(payloadBytes, size)
        if (payloadBytes > manifest.extractionBytesBudget) {
            fail(EnvironmentFailureCode.ENTRY_LIMIT, "rootfs archive exceeds the extraction byte budget")
        }
        regularFiles += member
        return RootfsEntryAction.Regular(member, size, executable)
    }

    private fun acceptSymlink(member: List<String>, rawTarget: String?): RootfsEntryAction {
        val target = rawTarget ?: fail(
            EnvironmentFailureCode.INVALID_ARCHIVE,
            "rootfs symlink has no target",
        )
        if (member in directories || member in regularFiles || member in symlinks) {
            fail(EnvironmentFailureCode.LINK_ESCAPE, "duplicate rootfs symlink member")
        }
        normalizeTarget(member.dropLast(1), target)
        symlinks += member
        return RootfsEntryAction.Symlink(member, target)
    }

    private fun acceptHardlink(member: List<String>, rawTarget: String?): RootfsEntryAction {
        val target = rawTarget ?: fail(
            EnvironmentFailureCode.INVALID_ARCHIVE,
            "rootfs hardlink has no target",
        )
        val targetMember = normalizeMember(target)
        if (targetMember !in regularFiles) {
            fail(
                EnvironmentFailureCode.LINK_ESCAPE,
                "rootfs hardlink must reference an earlier regular file",
            )
        }
        requireSafeParents(targetMember)
        if (member in directories || member in regularFiles || member in symlinks) {
            fail(EnvironmentFailureCode.PATH_ESCAPE, "duplicate rootfs hardlink member")
        }
        regularFiles += member
        return RootfsEntryAction.Hardlink(member, targetMember)
    }

    private fun normalizeMember(raw: String): List<String> {
        if (raw.isBlank() || raw.startsWith("/") || raw.contains('\u0000')) {
            fail(EnvironmentFailureCode.PATH_ESCAPE, "rootfs member path is not safe")
        }
        val components = raw.split('/')
        if (components.any { it == ".." }) {
            fail(EnvironmentFailureCode.PATH_ESCAPE, "rootfs member path contains dot-dot")
        }
        val clean = components.filter { it.isNotEmpty() && it != "." }
        if (clean.firstOrNull() != manifest.archiveRoot) {
            fail(EnvironmentFailureCode.PATH_ESCAPE, "rootfs member is outside the archive root")
        }
        return clean.drop(1)
    }

    private fun normalizeTarget(parent: List<String>, raw: String): List<String> {
        if (raw.isBlank() || raw.contains('\u0000')) {
            fail(EnvironmentFailureCode.LINK_ESCAPE, "rootfs symlink target is not safe")
        }
        val stack = if (raw.startsWith("/")) mutableListOf() else parent.toMutableList()
        raw.split('/').forEach { resolveTargetComponent(stack, it) }
        return stack
    }

    private fun resolveTargetComponent(stack: MutableList<String>, component: String) {
        when (component) {
            "", "." -> Unit
            ".." -> popTargetComponent(stack)
            else -> stack += component
        }
    }

    private fun popTargetComponent(stack: MutableList<String>) {
        if (stack.isEmpty()) {
            fail(EnvironmentFailureCode.LINK_ESCAPE, "rootfs symlink escapes the root")
        }
        stack.removeAt(stack.lastIndex)
    }

    private fun requireSafeParents(member: List<String>) {
        for (index in 1 until member.size) {
            if (member.subList(0, index) in symlinks) {
                fail(
                    EnvironmentFailureCode.LINK_ESCAPE,
                    "rootfs member traverses a symlink created earlier",
                )
            }
            if (member.subList(0, index) in regularFiles) {
                fail(EnvironmentFailureCode.PATH_ESCAPE, "rootfs member parent is a regular file")
            }
        }
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun fail(code: EnvironmentFailureCode, message: String): Nothing {
        throw EnvironmentCommandException(
            EnvironmentFailure(code, message, recoverable = true),
        )
    }

    private companion object {
        const val MAX_FILE_BYTES = 128L * 1024L * 1024L
    }
}
