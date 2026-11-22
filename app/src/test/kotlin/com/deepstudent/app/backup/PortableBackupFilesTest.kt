package com.deepstudent.app.backup

import com.deepstudent.platform.backupzip.CheckedZipArchive
import com.deepstudent.platform.security.DsbkCrypto
import com.deepstudent.platform.security.DsbkStagingArea
import com.deepstudent.services.backup.api.DomainAction
import com.deepstudent.services.backup.backend.ArchivePreflight
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** JVM/desktop ports only; no device or DataSpace slot-switch claim. */
class PortableBackupFilesTest {
    private lateinit var root: File
    private lateinit var stage: DsbkStagingArea
    private lateinit var files: PortableBackupFiles
    private val password = "fixture-password".toCharArray()
    private val limit = 16L * 1024 * 1024
    private val events = mutableListOf<PortableBackupEvent>()
    private val verifier = PortableArchiveVerifier { archive, check ->
        val plan = ArchivePreflight.inspectAuthenticated(archive, Long.MAX_VALUE, check).plan
        check(plan.canStage) { "authenticated preflight rejected fixture: ${plan.problems}" }
    }

    @Before fun setup() {
        root = Files.createTempDirectory("s214-portable-test-").toFile()
        stage = DsbkStagingArea(File(root, "staging"), limit)
        files = PortableBackupFiles(root, stage, DsbkCrypto.production(), limit, limit, limit, events::add)
    }

    @After fun teardown() { stage.close(); password.fill('\u0000'); root.deleteRecursively() }

    @Test fun desktopV1AndV2UnsealToTheSameIndependentFixture() {
        val fixtures = oracleFixtures()
        val expected = content(File(fixtures, "controlled-full.zip"))
        for (name in listOf("desktop-portable-v1-converted.zip", "desktop-portable-v2.zip")) {
            val source = File(fixtures, name).copyTo(File(root, name))
            val output = File(root, "result.zip").apply { writeText("old") }
            files.importPortableZip(source, output, password, verifier)
            assertContentEquals(expected, content(output))
            assertNoScratch()
        }
    }

    @Test fun androidExportIsAcceptedByFrozenDesktopImportPort() {
        val fixtures = oracleFixtures()
        val original = File(fixtures, "controlled-full.zip").copyTo(File(root, "original.zip"))
        val sensitive = sensitiveFixture(original)
        val oracle = findRoot().resolve("tools/s2/s2-14-desktop-interop.mjs")
        val node = System.getenv("S214_NODE") ?: "node"
        listOf(original, sensitive).forEachIndexed { index, reference ->
            val result = File(root, "portable-$index.zip")
            files.exportPortableZip(reference, result, password, verifier)
            val process = ProcessBuilder(node, oracle.path, "verify-android", "--input", result.path,
                "--password-env", "S214_TEST_PASSWORD", "--expect-canonical-sha256", canonical(reference))
                .redirectErrorStream(true).apply { environment()["S214_TEST_PASSWORD"] = "fixture-password" }.start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
            val report = Json.parseToJsonElement(output).jsonObject
            assertEquals("succeeded", report.getValue("desktopImport").jsonObject.getValue("result").jsonPrimitive.content)
            assertEquals(false, report.getValue("desktopSlotRestoreVerified").jsonPrimitive.boolean)
        }
        assertNoScratch()
    }

    @Test fun realDesktopExportWithKeysAndUserCodeIsUnsealedWithoutActivation() {
        val fixtures = oracleFixtures()
        val reference = sensitiveFixture(File(fixtures, "controlled-full.zip"))
        val sourceDir = File(root, "desktop-source").apply { mkdir() }
        zipMap(reference).forEach { (name, bytes) ->
            File(sourceDir, name).also { it.parentFile.mkdirs(); it.writeBytes(bytes) }
        }
        val desktopZip = File(root, "desktop-sensitive.zip")
        val script = """
            const [source, output, work] = process.argv.slice(1);
            const { createBackupCrypto } = await import('file:///home/xutao/deepstudent/dstu-node/host/platform/secrets/backup-crypto.ts');
            const native = await import('file:///home/xutao/deepstudent/dstu-node/host/platform/native/argon2/index.ts');
            const port = await import('file:///home/xutao/deepstudent/dstu-node/host/plugins/backup/src/zip.ts');
            const logger = { info() {}, warn() {}, error() {}, debug() {}, trace() {} };
            const ctx = { app: { dataDir: work }, get: () => undefined, logger: () => logger,
              secrets: { backup: createBackupCrypto({ nativeArgon2: native.argon2 }) } };
            await port.exportBackupToZip(ctx, source, { outputPath: output, encryptionPassword: process.env.S214_TEST_PASSWORD,
              includeChecksums: true, compressionLevel: 0 }, () => {}, () => false);
        """.trimIndent()
        val node = System.getenv("S214_NODE") ?: "node"
        val process = ProcessBuilder(node, "--input-type=module", "-e", script, sourceDir.path, desktopZip.path, root.path)
            .redirectErrorStream(true).apply { environment()["S214_TEST_PASSWORD"] = "fixture-password" }.start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
        val reconstructed = File(root, "desktop-reconstructed.zip")
        files.importPortableZip(desktopZip, reconstructed, password, verifier)
        assertContentEquals(content(reference), content(reconstructed))
        CheckedZipArchive(reconstructed).use { archive ->
            val plan = ArchivePreflight.inspectAuthenticated(archive, Long.MAX_VALUE).plan
            assertEquals(DomainAction.RETAIN_IN_SOURCE, plan.domains.single { it.id == "crypto" }.action)
            assertEquals(DomainAction.RETAIN_IN_SOURCE, plan.domains.single { it.id == "user-skills" }.action)
            assertTrue(plan.files.none { it.archivePath.startsWith("crypto/") || it.archivePath.startsWith("persistent/user_skills/") })
        }
        assertNoScratch()
    }

    @Test fun localKeyAndExecutableDomainsRemainInRebuiltArchiveButNotInActivePlan() {
        val source = sensitiveFixture()
        val output = File(root, "sealed.zip")
        files.exportPortableZip(source, output, password, verifier)
        val rebuilt = File(root, "rebuilt.zip")
        files.importPortableZip(output, rebuilt, password, verifier)
        assertContentEquals(content(source), content(rebuilt))
        CheckedZipArchive(rebuilt).use {
            val plan = ArchivePreflight.inspectAuthenticated(it, Long.MAX_VALUE).plan
            assertTrue(plan.problems.toString(), plan.canStage)
            assertEquals(DomainAction.RETAIN_IN_SOURCE, plan.domains.single { domain -> domain.id == "crypto" }.action)
            assertEquals(DomainAction.RETAIN_IN_SOURCE, plan.domains.single { domain -> domain.id == "user-skills" }.action)
            assertTrue(plan.files.none { file -> file.archivePath.startsWith("crypto/") || file.archivePath.startsWith("persistent/user_skills/") })
        }
        assertNoScratch()
    }

    @Test fun wrongPasswordAndPayloadTamperPreserveTarget() {
        val source = makePortable()
        val old = File(root, "old.zip").apply { writeText("old target") }
        var called = false
        expect(PortableFileError.AUTHENTICATION_FAILED) {
            files.importPortableZip(source, old, "incorrect-password".toCharArray(), PortableArchiveVerifier { _, _ -> called = true })
        }
        assertFalse(called)
        assertEquals("old target", old.readText())
        val tampered = zipMap(source).toMutableMap()
        tampered[PORTABLE_PAYLOAD] = tampered.getValue(PORTABLE_PAYLOAD).copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val broken = writeZip("broken.zip", tampered)
        expect(PortableFileError.PAYLOAD_MISMATCH) { files.importPortableZip(broken, old, password, verifier) }
        assertEquals("old target", old.readText())
        assertNoScratch()
        assertTrue(events.any { it.operation == "import" && it.status == "failed" && it.error == PortableFileError.AUTHENTICATION_FAILED })
        assertFalse(events.toString().contains("fixture-password"))
    }

    @Test fun payloadLengthAndAuthenticatedBytesCannotBeSwappedOrCorrupted() {
        val source = makePortable()
        val outer = zipMap(source).toMutableMap()
        val altered = outer.getValue(PORTABLE_PAYLOAD).copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        outer[PORTABLE_PAYLOAD] = altered
        outer[PORTABLE_MANIFEST] = replacePayloadDeclaration(outer.getValue(PORTABLE_MANIFEST), altered)
        val tampered = writeZip("updated-hash.zip", outer)
        val target = File(root, "target.zip").apply { writeText("old") }
        expect(PortableFileError.AUTHENTICATION_FAILED) { files.importPortableZip(tampered, target, password, verifier) }
        assertEquals("old", target.readText())
        assertNoScratch()
    }

    @Test fun outerOrAuthenticatedInnerCannotSmuggleAcrossLayers() {
        val source = makePortable()
        val outer = zipMap(source).toMutableMap()
        outer["crypto/secret"] = byteArrayOf(42)
        val badOuter = writeZip("bad-outer.zip", outer)
        val target = File(root, "target.zip").apply { writeText("old") }
        expect(PortableFileError.LAYER_VIOLATION) { files.importPortableZip(badOuter, target, password, verifier) }
        outer.remove("crypto/secret")
        val originalManifest = zipMap(safeFixture()).getValue(PORTABLE_MANIFEST)
        val rogueInner = writeZip("rogue-inner.zip", mapOf(PORTABLE_MANIFEST to originalManifest, "vfs.db" to byteArrayOf(1)))
        val encrypted = DsbkCrypto.production().encryptV1(rogueInner.readBytes(), password)
        outer[PORTABLE_PAYLOAD] = encrypted
        outer[PORTABLE_MANIFEST] = replacePayloadDeclaration(outer.getValue(PORTABLE_MANIFEST), encrypted)
        val badInner = writeZip("bad-inner.zip", outer)
        expect(PortableFileError.LAYER_VIOLATION) { files.importPortableZip(badInner, target, password, verifier) }
        assertEquals("old", target.readText())
        assertNoScratch()
    }

    @Test fun payloadOutsideManifestAndOverlappingAliasesAreRejected() {
        val source = makePortable()
        val entries = zipMap(source).toMutableMap()
        entries["VFS.DB"] = entries.getValue("vfs.db")
        val collision = writeZip("collision.zip", entries)
        val target = File(root, "target.zip").apply { writeText("old") }
        expect(PortableFileError.ENTRY_CONFLICT) { files.importPortableZip(collision, target, password, verifier) }
        assertEquals("old", target.readText())
        assertNoScratch()
    }

    @Test fun fullPlannerRechecksTheActualUnencryptedData() {
        val source = makePortable()
        val entries = zipMap(source).toMutableMap()
        entries["vfs.db"] = "same-len!!".toByteArray()
        assertEquals(zipMap(source).getValue("vfs.db").size, entries.getValue("vfs.db").size)
        val tampered = writeZip("bad-data.zip", entries)
        val target = File(root, "target.zip").apply { writeText("old") }
        expect(PortableFileError.VERIFICATION_FAILED) { files.importPortableZip(tampered, target, password, verifier) }
        assertEquals("old", target.readText())
        assertNoScratch()
    }

    @Test fun verifierCancellationAndMutationDoNotPublish() {
        val source = makePortable()
        val target = File(root, "target.zip").apply { writeText("old") }
        expect(PortableFileError.CANCELLED) {
            files.importPortableZip(source, target, password, PortableArchiveVerifier { _, _ -> throw CancellationException() })
        }
        expect(PortableFileError.SOURCE_CHANGED) {
            files.importPortableZip(source, target, password, PortableArchiveVerifier { archive, check ->
                verifier.verify(archive, check)
                root.listFiles()!!.single { it.name.startsWith(".portable-work-") }.resolve("reconstructed.zip").appendText("changed")
            })
        }
        assertEquals("old", target.readText())
        assertNoScratch()
    }

    @Test fun exportDetectsChangedInputAndRejectsUnsafeDestinations() {
        val source = safeFixture()
        val target = File(root, "target.zip").apply { writeText("old") }
        expect(PortableFileError.SOURCE_CHANGED) {
            files.exportPortableZip(source, target, password, PortableArchiveVerifier { archive, check ->
                verifier.verify(archive, check); source.appendText("changed")
            })
        }
        assertEquals("old", target.readText())
        val link = File(root, "link.zip")
        Files.createSymbolicLink(link.toPath(), source.toPath())
        expect(PortableFileError.FILE_INVALID) { files.exportPortableZip(link, target, password, verifier) }
        expect(PortableFileError.FILE_INVALID) { files.exportPortableZip(source, source, password, verifier) }
        assertNoScratch()
    }

    @Test fun cleanupOnlyDeletesAbandonedScratchNotALockedOperation() {
        val live = PortableScratch.create(root)
        val abandoned = File(root, ".portable-work-abandoned").apply { mkdir() }
        File(abandoned, ".lock").writeText("")
        File(abandoned, "inner.zip").writeText("private")
        assertEquals(1, files.cleanupAbandoned())
        assertTrue(live.directory.isDirectory)
        assertFalse(abandoned.exists())
        live.close()
        assertNoScratch()
    }

    @Test fun archiveAndInnerBudgetsLeaveExistingTargetUntouched() {
        val original = safeFixture()
        val target = File(root, "target.zip").apply { writeText("old") }
        val tinyInner = PortableBackupFiles(root, stage, DsbkCrypto.production(), limit, 256, limit)
        expect(PortableFileError.LIMIT_EXCEEDED) { tinyInner.exportPortableZip(original, target, password, verifier) }
        assertEquals("old", target.readText())
        val tinyArchive = PortableBackupFiles(root, stage, DsbkCrypto.production(), original.length() + 16, limit, limit)
        expect(PortableFileError.LIMIT_EXCEEDED) { tinyArchive.exportPortableZip(original, target, password, verifier) }
        assertEquals("old", target.readText())
        assertNoScratch()
    }

    @Test fun cleanupFailureIsReportedAndAbandonedScratchCanBeRemovedLater() {
        assumeTrue(Files.getFileStore(root.toPath()).supportsFileAttributeView("posix"))
        var cleanupReports = 0
        val scratch = PortableScratch.create(root) { cleanupReports++ }
        scratch.file("inner.zip").writeText("authenticated sensitive bytes")
        val readOnly = java.nio.file.attribute.PosixFilePermissions.fromString("r-x------")
        val normal = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
        Files.setPosixFilePermissions(scratch.directory.toPath(), readOnly)
        try {
            scratch.close()
            assertEquals(1, cleanupReports)
            assertTrue(scratch.directory.exists())
        } finally { if (scratch.directory.exists()) Files.setPosixFilePermissions(scratch.directory.toPath(), normal) }
        assertEquals(1, files.cleanupAbandoned())
        assertNoScratch()
    }

    private fun makePortable(): File {
        val source = safeFixture()
        return File(root, "sealed.zip").also { files.exportPortableZip(source, it, password, verifier) }
    }

    private fun safeFixture(): File {
        val fixture = findRoot().resolve("services/backup/backend/src/test/resources/desktop-v3-full.json")
        val map = linkedMapOf(PORTABLE_MANIFEST to fixture.readBytes())
        listOf("vfs", "llm_usage", "chat_v2", "mistakes").forEach { map["$it.db"] = "sqlite:$it".toByteArray() }
        return writeZip("plain.zip", map)
    }

    private fun sensitiveFixture(base: File = safeFixture()): File {
        val original = zipMap(base).toMutableMap()
        val secret = java.util.Base64.getEncoder().encode(ByteArray(32) { (it + 1).toByte() })
        val executable = "print('not executed')".toByteArray()
        val rootJson = Json.parseToJsonElement(original.getValue(PORTABLE_MANIFEST).decodeToString()).jsonObject.toMutableMap()
        val files = rootJson.getValue("files").jsonArray.toMutableList()
        fun record(path: String, bytes: ByteArray) = JsonObject(mapOf("path" to JsonPrimitive(path),
            "size" to JsonPrimitive(bytes.size), "sha256" to JsonPrimitive(sha(bytes))))
        files += record("crypto/.master_key", secret)
        files += record("persistent/user_skills/tool/run.py", executable)
        rootJson["files"] = JsonArray(files)
        rootJson["key_policy"] = JsonPrimitive("included_local")
        val coverage = rootJson.getValue("coverage").jsonObject.toMutableMap()
        val domains = coverage.getValue("domains").jsonObject.toMutableMap()
        fun complete(id: String, path: String, bytes: ByteArray) {
            domains[id] = JsonObject(domains.getValue(id).jsonObject.toMutableMap().apply {
                put("status", JsonPrimitive("complete")); put("paths", JsonArray(listOf(JsonPrimitive(path))))
                put("file_count", JsonPrimitive(1)); put("total_size", JsonPrimitive(bytes.size))
            })
        }
        complete("crypto", "crypto/.master_key", secret)
        complete("user-skills", "persistent/user_skills/tool/run.py", executable)
        coverage["domains"] = JsonObject(domains)
        rootJson["coverage"] = JsonObject(coverage)
        original[PORTABLE_MANIFEST] = JsonObject(rootJson).toString().toByteArray()
        original["crypto/.master_key"] = secret
        original["persistent/user_skills/tool/run.py"] = executable
        return writeZip("sensitive.zip", original)
    }

    private fun replacePayloadDeclaration(manifest: ByteArray, payload: ByteArray): ByteArray {
        val root = Json.parseToJsonElement(manifest.decodeToString()).jsonObject.toMutableMap()
        root["files"] = JsonArray(root.getValue("files").jsonArray.map {
            if (it.jsonObject["path"]?.jsonPrimitive?.content != PORTABLE_PAYLOAD) it else JsonObject(it.jsonObject.toMutableMap().apply {
                put("size", JsonPrimitive(payload.size)); put("sha256", JsonPrimitive(sha(payload)))
            })
        })
        return JsonObject(root).toString().toByteArray()
    }

    private fun writeZip(name: String, map: Map<String, ByteArray>): File = File(root, name).apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            map.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
        }
    }
    private fun zipMap(file: File): Map<String, ByteArray> = ZipFile(file).use { zip ->
        zip.entries().asSequence().filterNot { it.isDirectory }.associate { it.name to zip.getInputStream(it).readBytes() }
    }
    private fun content(file: File): Map<String, String> = zipMap(file).mapValues { sha(it.value) }
    private fun canonical(file: File): String {
        val records = zipMap(file).toSortedMap().entries.joinToString("") { (path, bytes) ->
            "$path\t${bytes.size}\t${sha(bytes)}\n"
        }
        return sha(records.toByteArray(Charsets.UTF_8))
    }
    private fun assertContentEquals(expected: Map<String, String>, actual: Map<String, String>) = assertEquals(expected, actual)
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun assertNoScratch() = assertTrue(root.listFiles()!!.none { it.name.startsWith(".portable-work-") })
    private fun expect(code: PortableFileError, action: () -> Unit) {
        val error = try { action(); fail("expected $code"); null } catch (error: PortableFileException) { error }
        assertEquals(code, error!!.error)
    }
    private fun oracleFixtures(): File {
        val path = System.getenv("S214_DESKTOP_FIXTURES")
        assumeTrue("Set S214_DESKTOP_FIXTURES to the independent frozen desktop oracle output", path != null)
        return File(path!!)
    }
    private fun findRoot(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "tools/s2/s2-14-desktop-interop.mjs").isFile }
}
