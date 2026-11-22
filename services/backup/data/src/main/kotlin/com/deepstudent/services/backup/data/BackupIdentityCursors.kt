package com.deepstudent.services.backup.data

import com.deepstudent.core.common.id.Digests
import com.deepstudent.services.backup.backend.ContinuityCursor
import com.deepstudent.services.backup.backend.ContinuityValidation

/** Resolve only authenticated aliases: a 16-hex source may be either a raw ID or another ID's hash. */
internal fun backupIdentityCursorAliases(
    matched: ContinuityValidation.Matched,
    aliases: BackupIdentityPeerAliases,
): Map<String, Long> {
    val sources = matched.state.cursors.map { it.uploaderDeviceId }.toSet()
    if (aliases.canonicalBySource.keys != sources) throw BackupIdentityException(BackupIdentityFailure.CURSOR_ALIAS_CONFLICT)
    val owners = mutableMapOf<String, String>()
    val result = mutableMapOf<String, Long>()
    for (cursor in matched.state.cursors) backupIdentityAddCursor(cursor, aliases, owners, result)
    return result
}

private fun backupIdentityAddCursor(
    cursor: ContinuityCursor,
    aliases: BackupIdentityPeerAliases,
    owners: MutableMap<String, String>,
    result: MutableMap<String, Long>,
) {
    val source = cursor.uploaderDeviceId
    val canonical = aliases.canonicalBySource.getValue(source)
    if (!backupIdentitySafeDevice(canonical)) throw BackupIdentityException(BackupIdentityFailure.CURSOR_ALIAS_CONFLICT)
    // Same frozen hash as SyncWireCodec.deviceHash; no cross-capability production dependency.
    val hashed = Digests.sha256HexUtf8(canonical).take(16)
    if (source != canonical && source != hashed) throw BackupIdentityException(BackupIdentityFailure.CURSOR_ALIAS_CONFLICT)
    for (key in setOf(canonical, hashed)) {
        val owner = owners.putIfAbsent(key, canonical)
        if (owner != null && owner != canonical) throw BackupIdentityException(BackupIdentityFailure.CURSOR_ALIAS_CONFLICT)
        result[key] = maxOf(result[key] ?: 0L, cursor.lastSeq)
    }
}

private fun backupIdentitySafeDevice(id: String): Boolean =
    id.isNotBlank() && id.length <= 160 && id != "." && id != ".." && backupIdentityValidCharacters(id)

private fun backupIdentityValidCharacters(id: String): Boolean {
    var index = 0
    while (index < id.length) {
        val char = id[index++]
        if (backupIdentityInvalidChar(char)) return false
        if (Character.isHighSurrogate(char) && (index >= id.length || !Character.isLowSurrogate(id[index++]))) return false
    }
    return true
}

private fun backupIdentityInvalidChar(char: Char): Boolean =
    char == '/' || char == '\\' || char.isWhitespace() || char.isISOControl() || Character.isLowSurrogate(char)
