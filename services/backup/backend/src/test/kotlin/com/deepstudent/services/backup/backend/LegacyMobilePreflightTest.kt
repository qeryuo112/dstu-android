package com.deepstudent.services.backup.backend

import com.deepstudent.services.backup.api.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipFile

class LegacyMobilePreflightTest {
    companion object {
        private val original: Map<String, ByteArray> by lazy {
            var directory: File? = File("").absoluteFile
            var fixture: File? = null
            while (directory != null && fixture == null) {
                val candidate = File(directory, "fixtures/legacy-mobile/legacy-mobile-standard-standard-v1.zip")
                if (candidate.isFile) fixture = candidate else directory = directory.parentFile
            }
            ZipFile(requireNotNull(fixture)).use { zip ->
                zip.entries().asSequence().associate { it.name to zip.getInputStream(it).use(InputStream::readBytes) }
            }
        }
    }

    private class MemoryArchive(
        private val content: Map<String, ByteArray>,
        private val fragmented: Boolean = false,
        val opened: MutableList<String> = mutableListOf(),
    ) : RestoreArchive {
        override fun inspect() = ArchiveCatalog(content.map { (path, bytes) -> ArchiveEntry(path, ArchiveEntryKind.FILE, bytes.size.toLong()) })
        override fun open(path: String): InputStream {
            opened += path
            val stream = ByteArrayInputStream(content.getValue(path))
            if (!fragmented) return stream
            return object : FilterInputStream(stream) {
                var calls = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    super.read(buffer, offset, if (calls++ < 20) minOf(length, 1) else length)
            }
        }
        override fun close() = Unit
    }

    private fun root(input: Map<String, ByteArray> = original): JsonObject =
        Json.parseToJsonElement(input.getValue("manifest.json").toString(Charsets.UTF_8)).jsonObject

    private fun withRoot(input: Map<String, ByteArray>, root: JsonObject): Map<String, ByteArray> =
        input + ("manifest.json" to root.toString().toByteArray())

    private fun setRoot(vararg pairs: Pair<String, JsonElement>): Map<String, ByteArray> =
        withRoot(original, JsonObject(root() + pairs))

    private fun mutateDomain(input: Map<String, ByteArray>, id: String, update: (JsonObject) -> JsonObject): Map<String, ByteArray> {
        val r = root(input)
        val coverage = r.getValue("coverage").jsonObject
        val domains = coverage.getValue("domains").jsonObject
        val changed = JsonObject(domains + (id to update(domains.getValue(id).jsonObject)))
        return withRoot(input, JsonObject(r + ("coverage" to JsonObject(coverage + ("domains" to changed)))))
    }

    private fun replacePayload(path: String, payload: ByteArray): Map<String, ByteArray> {
        val r = root()
        val oldSize = original.getValue(path).size
        val files = r.getValue("files").jsonArray.map { value ->
            val file = value.jsonObject
            if (file.getValue("path").jsonPrimitive.content != path) value else JsonObject(file + mapOf(
                "size" to JsonPrimitive(payload.size), "sha256" to JsonPrimitive(digest(payload)),
            ))
        }
        var changed = withRoot(original + (path to payload), JsonObject(r + ("files" to JsonArray(files))))
        val domains = root(changed).getValue("coverage").jsonObject.getValue("domains").jsonObject
        for ((id, value) in domains) {
            val domain = value.jsonObject
            if (domain.getValue("paths").jsonArray.any { it.jsonPrimitive.content == path }) {
                changed = mutateDomain(changed, id) { d ->
                    JsonObject(d + ("total_size" to JsonPrimitive(d.getValue("total_size").jsonPrimitive.long - oldSize + payload.size)))
                }
            }
        }
        return changed
    }

    private fun addDomainFile(input: Map<String, ByteArray>, path: String, domainIds: List<String>): Map<String, ByteArray> {
        val bytes = "untrusted content".toByteArray()
        val r = root(input)
        val file = JsonObject(mapOf("path" to JsonPrimitive(path), "size" to JsonPrimitive(bytes.size), "sha256" to JsonPrimitive(digest(bytes))))
        var changed = withRoot(input + (path to bytes), JsonObject(r + ("files" to JsonArray(r.getValue("files").jsonArray + file))))
        for (id in domainIds) changed = mutateDomain(changed, id) { d ->
            JsonObject(d + mapOf(
                "status" to JsonPrimitive("complete"), "paths" to JsonArray(d.getValue("paths").jsonArray + JsonPrimitive(path)),
                "file_count" to JsonPrimitive(d.getValue("file_count").jsonPrimitive.long + 1),
                "total_size" to JsonPrimitive(d.getValue("total_size").jsonPrimitive.long + bytes.size),
            ))
        }
        return changed
    }

    private fun inspect(input: Map<String, ByteArray> = original, available: Long = Long.MAX_VALUE): RestorePlan =
        ArchivePreflight.inspect(MemoryArchive(input), available).plan

    private fun rejected(input: Map<String, ByteArray>, code: RestoreProblemCode): RestorePlan = inspect(input).also {
        assertFalse(it.toString(), it.canStage)
        assertFalse(it.canSwitchSlot)
        assertTrue(it.problems.toString(), it.problems.any { problem -> problem.code == code })
    }

    @Test fun realPreflightReadsAllPayloadsAndReportsOnlyUntrustedDeclarations() {
        val archive = MemoryArchive(original, fragmented = true)
        val receipt = ArchivePreflight.inspect(archive, Long.MAX_VALUE)
        val plan = receipt.plan
        assertTrue(plan.problems.toString(), plan.canStage)
        assertTrue(receipt.isFor(archive))
        assertEquals(original.keys, archive.opened.toSet())
        assertEquals(original.size, archive.opened.size)
        assertEquals(original.filterKeys { it != "manifest.json" }.values.sumOf { it.size.toLong() } * 2, plan.requiredBytes)
        assertEquals(LegacyImportReport("3.0.0", "0.20.0", mapOf(
            "vfs" to 20261111L, "llm_usage" to 20260826L, "chat_v2" to 20261111L, "mistakes" to 20261005L,
        )), plan.legacyImport)
        assertEquals(DomainAction.PRESERVE_INACTIVE, plan.domains.single { it.id == "audit" }.action)
        assertEquals("legacy-import/audit.db", plan.files.single { it.archivePath == "databases/audit.db" }.slotRelativePath)
        assertTrue(plan.files.none { it.slotRelativePath == "databases/audit.db" })
        assertFalse(plan.canSwitchSlot)
    }

    @Test fun pureDesktopPlannerKeepsAuditTrustEvenForSameMobileManifest() {
        val manifest = ManifestCodec.decode(original.getValue("manifest.json").toString(Charsets.UTF_8))
        val catalog = MemoryArchive(original).inspect()
        val evidence = manifest.files.map { f -> ShaEvidence(f.path, f.size, digest(original.getValue(f.path))) }
        val result = RestorePlanner.plan(manifest, catalog, evidence, Long.MAX_VALUE, "a".repeat(64))
        assertNull(result.legacyImport)
        assertEquals(DomainAction.EXPLICIT_TRUST_REQUIRED, result.domains.single { it.id == "audit" }.action)
        assertTrue(result.problems.any { it.code == RestoreProblemCode.EXPLICIT_TRUST_REQUIRED && it.domainId == "audit" })
        assertTrue(result.files.none { it.archivePath == "databases/audit.db" })
        assertFalse(result.canStage)
    }

    @Test fun internalMobilePlannerAlsoRequiresExplicitMobilePlatform() {
        val manifest = ManifestCodec.decode(original.getValue("manifest.json").toString(Charsets.UTF_8))
        val desktop = manifest.copy(identity = manifest.identity.copy(platform = "linux"))
        val evidence = desktop.files.map { file -> ShaEvidence(file.path, file.size, digest(original.getValue(file.path))) }
        val result = RestorePlanner.planMobile(desktop, MemoryArchive(original).inspect(), evidence, Long.MAX_VALUE, "a".repeat(64))
        assertNull(result.legacyImport)
        assertTrue(result.problems.any { it.code == RestoreProblemCode.INVALID_MANIFEST })
        assertEquals(DomainAction.EXPLICIT_TRUST_REQUIRED, result.domains.single { it.id == "audit" }.action)
        assertTrue(result.files.none { it.archivePath == "databases/audit.db" })
        assertFalse(result.canStage)
    }

    @Test fun desktopAndAlmostMobilePlatformNeverUseMobileAuditException() {
        for (platform in listOf("linux", "Mobile", "mobile ")) {
            val result = inspect(setRoot("platform" to JsonPrimitive(platform)))
            assertNull(result.legacyImport)
            assertFalse(result.canStage)
            assertEquals(DomainAction.EXPLICIT_TRUST_REQUIRED, result.domains.single { it.id == "audit" }.action)
            assertTrue(result.files.none { it.archivePath == "databases/audit.db" })
        }
    }

    @Test fun versionFullIncrementalKeyAndCoverageAreStrictlyGated() {
        val variants = listOf(
            setRoot("version" to JsonPrimitive("3.0.1")) to RestoreProblemCode.UNSUPPORTED_VERSION,
            setRoot("snapshot_kind" to JsonPrimitive("partial_overlay")) to RestoreProblemCode.PARTIAL_NOT_SLOTABLE,
            setRoot("snapshot_kind" to JsonPrimitive("legacy_candidate")) to RestoreProblemCode.UNSUPPORTED_VERSION,
            setRoot("is_incremental" to JsonPrimitive(true)) to RestoreProblemCode.INCREMENTAL_UNSUPPORTED,
            setRoot("incremental_base" to JsonPrimitive("base-id")) to RestoreProblemCode.INCREMENTAL_UNSUPPORTED,
            setRoot("key_policy" to JsonPrimitive("included_local")) to RestoreProblemCode.INVALID_KEY_POLICY,
            setRoot("key_policy" to JsonPrimitive("included_encrypted")) to RestoreProblemCode.INVALID_KEY_POLICY,
            setRoot("key_policy" to JsonPrimitive("excluded_portable")) to RestoreProblemCode.INVALID_KEY_POLICY,
            setRoot("coverage" to JsonNull) to RestoreProblemCode.INVALID_COVERAGE,
        )
        for ((input, code) in variants) {
            val result = rejected(input, code)
            assertNull(result.legacyImport)
            assertNotEquals(DomainAction.PRESERVE_INACTIVE, result.domains.singleOrNull { it.id == "audit" }?.action)
        }
    }

    @Test fun schemaVersionsAreCompleteSupportedAndSourceVersionIsDisplaySafe() {
        val versions = root().getValue("schema_versions").jsonObject
        for (id in listOf("vfs", "llm_usage", "chat_v2", "mistakes")) {
            val result = rejected(setRoot("schema_versions" to JsonObject(versions - id)), RestoreProblemCode.UNSUPPORTED_SCHEMA)
            assertNull(result.legacyImport)
        }
        for (v in listOf(0L, -1L, Long.MAX_VALUE)) {
            rejected(setRoot("schema_versions" to JsonObject(versions + ("vfs" to JsonPrimitive(v)))), RestoreProblemCode.UNSUPPORTED_SCHEMA)
        }
        rejected(setRoot("schema_versions" to JsonObject(versions + ("unknown" to JsonPrimitive(1)))), RestoreProblemCode.UNSUPPORTED_SCHEMA)
        for (appVersion in listOf("", " 0.20", "a\nb", "1\u202e2", "v".repeat(129), "<script>")) {
            val result = rejected(setRoot("app_version" to JsonPrimitive(appVersion)), RestoreProblemCode.INVALID_MANIFEST)
            assertNull(result.legacyImport)
        }
        assertTrue(inspect(setRoot("app_version" to JsonPrimitive("0.20.0-beta+1"))).canStage)
    }

    @Test fun auditIsOptionalButOtherAuditPathsAndMetadataStayUntrusted() {
        val r = root()
        val files = r.getValue("files").jsonArray.filterNot { it.jsonObject.getValue("path").jsonPrimitive.content == "databases/audit.db" }
        val noFile = withRoot(original - "databases/audit.db", JsonObject(r + ("files" to JsonArray(files))))
        val absent = mutateDomain(noFile, "audit") { d -> JsonObject(d + mapOf(
            "status" to JsonPrimitive("absent"), "paths" to JsonArray(emptyList()), "file_count" to JsonPrimitive(0), "total_size" to JsonPrimitive(0),
        )) }
        val result = inspect(absent)
        assertTrue(result.problems.toString(), result.canStage)
        assertEquals(DomainAction.NO_CONTENT, result.domains.single { it.id == "audit" }.action)
        assertTrue(result.files.none { it.archivePath == "databases/audit.db" })
        val extra = addDomainFile(original, "databases/audit.db/other", listOf("audit"))
        val unsafe = rejected(extra, RestoreProblemCode.EXPLICIT_TRUST_REQUIRED)
        assertEquals(DomainAction.EXPLICIT_TRUST_REQUIRED, unsafe.domains.single { it.id == "audit" }.action)
        assertTrue(unsafe.files.none { it.slotRelativePath == "legacy-import/audit.db" })
        val wrongMetadata = mutateDomain(original, "audit") { JsonObject(it + ("restore_trust" to JsonPrimitive("data"))) }
        rejected(wrongMetadata, RestoreProblemCode.INVALID_COVERAGE)
    }

    @Test fun noSidecarsOrOtherUnownedFilesAreAccepted() {
        for (path in listOf("checksums.sha256", "legacy.continuity.json", "sidecar/continuity.json")) {
            rejected(original + (path to "untrusted".toByteArray()), RestoreProblemCode.UNDECLARED_ARCHIVE_ENTRY)
        }
    }

    @Test fun unsupportedAndExecutableDomainsRemainBlockedForMobile() {
        val images = addDomainFile(original, "assets/images/image.png", listOf("asset-root:images"))
        val imageResult = rejected(images, RestoreProblemCode.NO_ANDROID_TARGET)
        assertTrue(imageResult.files.none { it.archivePath == "assets/images/image.png" })
        val agent = "assets/workspaces/agents/run.js"
        val withAgent = addDomainFile(original, agent, listOf("asset-root:workspaces", "agents"))
        val agentResult = rejected(withAgent, RestoreProblemCode.EXPLICIT_TRUST_REQUIRED)
        assertTrue(agentResult.files.none { it.archivePath == agent })
        assertEquals(DomainAction.EXPLICIT_TRUST_REQUIRED, agentResult.domains.single { it.id == "agents" }.action)
    }

    @Test fun authenticatedDesktopAllowsDataWithoutRoutingKeysScriptsOrUnsupportedAssets() {
        val archive = MemoryArchive(desktopWithInactiveDomains(), fragmented = true)
        val plan = ArchivePreflight.inspectAuthenticated(archive, Long.MAX_VALUE).plan
        assertTrue(plan.problems.toString(), plan.canStage)
        assertEquals(RestoreAdmission.AUTHENTICATED_PACKAGE, plan.source.admission)
        assertEquals(desktopWithInactiveDomains().keys, archive.opened.toSet())
        val retained = plan.domains.filter { it.action == DomainAction.RETAIN_IN_SOURCE }.map { it.id }.toSet()
        assertEquals(setOf("crypto", "agents", "asset-root:workspaces", "audit", "asset-root:images"), retained)
        assertEquals(4, plan.files.count { it.slotRelativePath.startsWith("databases/") })
        assertTrue(plan.files.all { it.slotRelativePath.startsWith("databases/") || it.slotRelativePath.startsWith("vfs_blobs/") })
        assertFalse(inspect(desktopWithInactiveDomains()).canStage)
    }

    @Test fun retainedOnlyBytesAndCoverageAreStillValidatedBeforeAnyAuthenticatedPlanCanStage() {
        val source = desktopWithInactiveDomains()
        val path = "crypto/.master_key"
        val altered = source + (path to source.getValue(path).clone().apply { this[0] = 0 })
        val tampered = ArchivePreflight.inspectAuthenticated(MemoryArchive(altered), Long.MAX_VALUE).plan
        assertFalse(tampered.canStage)
        assertTrue(tampered.problems.any { it.code == RestoreProblemCode.HASH_MISMATCH && it.archivePath == path })
        val reassigned = mutateDomain(source, "agents") { JsonObject(it + ("restore_trust" to JsonPrimitive("data"))) }
        val untrusted = ArchivePreflight.inspectAuthenticated(MemoryArchive(reassigned), Long.MAX_VALUE).plan
        assertFalse(untrusted.canStage)
        assertTrue(untrusted.problems.any { it.code == RestoreProblemCode.INVALID_COVERAGE })
        val invalidMobile = withRoot(source, JsonObject(root(source) + ("platform" to JsonPrimitive("mobile"))))
        assertFalse(ArchivePreflight.inspectAuthenticated(MemoryArchive(invalidMobile), Long.MAX_VALUE).plan.canStage)
    }

    private fun desktopWithInactiveDomains(): Map<String, ByteArray> {
        val desktop = setRoot("platform" to JsonPrimitive("linux"), "key_policy" to JsonPrimitive("included_local"))
        val keys = addDomainFile(desktop, "crypto/.master_key", listOf("crypto"))
        val scripts = addDomainFile(keys, "assets/workspaces/agents/run.js", listOf("asset-root:workspaces", "agents"))
        return addDomainFile(scripts, "assets/images/image.png", listOf("asset-root:images"))
    }

    @Test fun cryptoCoverageStillMustAgreeWithNotPresentKeyPolicy() {
        val crypto = addDomainFile(original, "crypto/keys.bin", listOf("crypto"))
        val result = rejected(crypto, RestoreProblemCode.INVALID_KEY_POLICY)
        assertEquals(DomainAction.EXPLICIT_TRUST_REQUIRED, result.domains.single { it.id == "crypto" }.action)
        assertTrue(result.files.none { it.archivePath == "crypto/keys.bin" })
    }

    @Test fun sqliteHeaderAndSizeAreCheckedFromObservedBytesEvenWhenHashesMatch() {
        for (path in listOf("vfs.db", "llm_usage.db", "chat_v2.db", "mistakes.db", "databases/audit.db")) {
            val broken = original.getValue(path).copyOf().also { it[0] = 0 }
            val result = rejected(replacePayload(path, broken), RestoreProblemCode.INVALID_DATABASE)
            assertTrue(result.problems.any { it.code == RestoreProblemCode.INVALID_DATABASE && it.archivePath == path })
            assertTrue(result.problems.none { it.code == RestoreProblemCode.HASH_MISMATCH })
        }
        val header = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        for (size in listOf(16, 511, 513, 1025)) {
            val bytes = ByteArray(size).also { header.copyInto(it) }
            rejected(replacePayload("vfs.db", bytes), RestoreProblemCode.INVALID_DATABASE)
        }
    }

    @Test fun hashMismatchCannotBeHiddenInAuditOrBlob() {
        val blob = original.keys.single { it.startsWith("assets/vfs_blobs/") }
        for (path in listOf("databases/audit.db", blob)) {
            val bytes = original.getValue(path).copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            val result = rejected(original + (path to bytes), RestoreProblemCode.HASH_MISMATCH)
            assertTrue(result.problems.any { it.code == RestoreProblemCode.HASH_MISMATCH && it.archivePath == path })
        }
    }

    @Test fun budgetIncludesAuditAndRejectsBeforeOpeningPayloads() {
        val required = original.filterKeys { it != "manifest.json" }.values.sumOf { it.size.toLong() } * 2
        val archive = MemoryArchive(original)
        val result = ArchivePreflight.inspect(archive, required - 1).plan
        assertFalse(result.canStage)
        assertEquals(required, result.requiredBytes)
        assertTrue(result.problems.any { it.code == RestoreProblemCode.INSUFFICIENT_SPACE })
        assertEquals(listOf("manifest.json"), archive.opened)
        assertTrue(inspect(available = required).canStage)
    }

    @Test fun bothFrozenBlobRootsMapToTheSameSlotPath() {
        val oldPath = original.keys.single { it.startsWith("assets/vfs_blobs/") }
        val alias = oldPath.removePrefix("assets/")
        val r = root()
        val files = r.getValue("files").jsonArray.map { value ->
            val file = value.jsonObject
            if (file.getValue("path").jsonPrimitive.content == oldPath) JsonObject(file + ("path" to JsonPrimitive(alias))) else value
        }
        var changed = withRoot(original - oldPath + (alias to original.getValue(oldPath)), JsonObject(r + ("files" to JsonArray(files))))
        changed = mutateDomain(changed, "asset-root:vfs_blobs") { d -> JsonObject(d + ("paths" to JsonArray(listOf(JsonPrimitive(alias))))) }
        val viaFile = inspect(changed)
        assertTrue(viaFile.problems.toString(), viaFile.canStage)
        assertEquals(alias, viaFile.files.single { it.archivePath == alias }.slotRelativePath)
        assertEquals(alias, inspect().files.single { it.archivePath == oldPath }.slotRelativePath)

        val aliasRoot = root(changed)
        val file = aliasRoot.getValue("files").jsonArray.single { it.jsonObject.getValue("path").jsonPrimitive.content == alias }.jsonObject
        val withoutBlob = aliasRoot.getValue("files").jsonArray.filterNot { it.jsonObject.getValue("path").jsonPrimitive.content == alias }
        val asset = JsonObject(mapOf(
            "asset_type" to JsonPrimitive("vfs_blobs"), "relative_path" to JsonPrimitive(alias),
            "original_path" to JsonPrimitive(alias), "size" to file.getValue("size"),
            "checksum" to file.getValue("sha256"), "is_directory" to JsonPrimitive(false),
        ))
        val assetRoot = JsonObject(aliasRoot + mapOf("files" to JsonArray(withoutBlob), "assets" to JsonObject(mapOf("files" to JsonArray(listOf(asset))))))
        val viaAsset = inspect(withRoot(changed, assetRoot))
        assertTrue(viaAsset.problems.toString(), viaAsset.canStage)
        assertEquals(alias, viaAsset.files.single { it.archivePath == alias }.slotRelativePath)

        val desktop = withRoot(changed, JsonObject(root(changed) + ("platform" to JsonPrimitive("linux"))))
        assertNull(inspect(desktop).legacyImport)
        assertEquals(DomainAction.EXPLICIT_TRUST_REQUIRED, inspect(desktop).domains.single { it.id == "audit" }.action)
    }

    @Test fun aliasedBlobEntriesCannotOverwriteTheSameDestination() {
        val oldPath = original.keys.single { it.startsWith("assets/vfs_blobs/") }
        val alias = oldPath.removePrefix("assets/")
        val r = root()
        val file = r.getValue("files").jsonArray.single { it.jsonObject.getValue("path").jsonPrimitive.content == oldPath }.jsonObject
        val files = JsonArray(r.getValue("files").jsonArray + JsonObject(file + ("path" to JsonPrimitive(alias))))
        var changed = withRoot(original + (alias to original.getValue(oldPath)), JsonObject(r + ("files" to files)))
        changed = mutateDomain(changed, "asset-root:vfs_blobs") { d -> JsonObject(d + mapOf(
            "paths" to JsonArray(d.getValue("paths").jsonArray + JsonPrimitive(alias)),
            "file_count" to JsonPrimitive(2),
            "total_size" to JsonPrimitive(d.getValue("total_size").jsonPrimitive.long * 2),
        )) }
        rejected(changed, RestoreProblemCode.TARGET_CONFLICT)
    }

}
