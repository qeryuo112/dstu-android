package com.deepstudent.services.backup.backend

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuityTrustTest {
    private val fixture = ContinuityFixture

    @Test fun rejectsEveryIndependentArchiveAndManifestMismatch() {
        val trust = fixture.trust
        val archive = trust.archive
        val manifest = archive.manifest
        val cases = listOf(
            archive.copy(sha256 = "f".repeat(64)), archive.copy(sha256 = archive.sha256.uppercase()),
            archive.copy(bytes = archive.bytes + 1), archive.copy(bytes = 0),
            archive.copy(manifest = manifest.copy(identity = manifest.identity.copy(backupId = "other"))),
            archive.copy(manifest = manifest.copy(identity = manifest.identity.copy(snapshotEpoch = "other"))),
            archive.copy(manifest = manifest.copy(identity = manifest.identity.copy(createdAt = "other"))),
            archive.copy(manifest = manifest.copy(identity = manifest.identity.copy(version = "3.0.1"))),
            archive.copy(manifest = manifest.copy(identity = manifest.identity.copy(appVersion = "0.21.0"))),
            archive.copy(manifest = manifest.copy(identity = manifest.identity.copy(platform = "linux"))),
            archive.copy(manifest = manifest.copy(payload = manifest.payload.copy(schemaVersions = manifest.schemaVersions + ("vfs" to 1L)))),
            archive.copy(manifest = manifest.copy(payload = manifest.payload.copy(schemaVersions = manifest.schemaVersions - "vfs"))),
            archive.copy(manifest = manifest.copy(coverage = null)),
        )
        for (case in cases) fixture.rejected(ContinuityValidator.validate(fixture.bytes, trust.copy(archive = case)))
    }

    @Test fun rejectsDifferentInstallationRepositoryOrWriterConfirmation() {
        val trust = fixture.trust
        val source = trust.installation
        val repo = trust.repository
        val writer = trust.writer
        val cases = listOf(
            trust.copy(installation = source.copy(applicationId = "other")),
            trust.copy(installation = source.copy(versionName = "0.21.0")),
            trust.copy(installation = source.copy(versionCode = source.versionCode + 1)),
            trust.copy(installation = source.copy(installationId = "other")),
            trust.copy(repository = repo.copy(instanceId = "other")),
            trust.copy(repository = repo.copy(provider = "other")),
            trust.copy(repository = repo.copy(endpointHint = "file:///other")),
            trust.copy(writer = writer.copy(deviceId = "other")),
            trust.copy(writer = writer.copy(stopped = false)),
            trust.copy(writer = writer.copy(unchangedSinceSnapshot = false)),
        )
        for (case in cases) fixture.rejected(ContinuityValidator.validate(fixture.bytes, case))
        fixture.rejected(ContinuityValidator.validate(fixture.bytes, cases[cases.lastIndex]), ContinuityFailure.WRITER_NOT_CONFIRMED)
    }

    @Test fun mismatchedSidecarSnapshotSourceIdentityAndBindingAreNotSelfTrusted() {
        val snapshots = mapOf(
            "zip_sha256" to JsonPrimitive("f".repeat(64)), "zip_bytes" to JsonPrimitive(1),
            "snapshot_epoch" to JsonPrimitive("other"), "backup_id" to JsonPrimitive("other"),
            "manifest_created_at" to JsonPrimitive("other"), "manifest_version" to JsonPrimitive("3.0.1"),
            "app_version" to JsonPrimitive("0.21.0"), "key_policy" to JsonPrimitive("included_local"),
        )
        for ((key, value) in snapshots) fixture.rejected(fixture.root.changeObject("snapshot") { it.change(key, value) })
        for (key in listOf("applicationId", "versionName", "installationId")) {
            fixture.rejected(fixture.root.changeObject("source_installation") { it.change(key, JsonPrimitive("other")) })
        }
        fixture.rejected(fixture.root.changeObject("source_installation") { it.change("versionCode", JsonPrimitive(1)) })
        for (key in listOf("mode", "reason", "instance_id", "device_id")) {
            fixture.rejected(fixture.root.changeObject("identity") { it.change(key, JsonPrimitive("other")) })
        }
        for (key in listOf("instance_id", "provider", "endpoint_hint")) {
            fixture.rejected(fixture.root.changeState("instanceBinding") { rows -> rows.changeRow(0) { it.change(key, JsonPrimitive("other")) } })
        }
        fixture.rejected(fixture.root.changeObject("snapshot") { snap ->
            snap.changeObject("schema_versions") { it.change("vfs", JsonPrimitive(1)) }
        })
    }

    @Test fun pairedProviderHintsWithoutCredentialsAreComparedExactly() {
        val cases = listOf(
            "WebDAV" to "webdav|endpoint=https://example.com|user=alice|root=sync",
            "S3" to "s3|endpoint=https://example.com|bucket=study|root=sync",
            "FTP" to "ftp|host=example.com|port=21|user=alice|tls=false|root=sync",
        )
        for ((provider, endpoint) in cases) {
            val root = fixture.root.removeKey("expected").changeState("instanceBinding") { rows ->
                rows.changeRow(0) { it.change("provider", JsonPrimitive(provider)).change("endpoint_hint", JsonPrimitive(endpoint)) }
            }
            val bytes = fixture.input(root)
            val trust = fixture.testTrust(bytes).copy(repository = ContinuityRepository("default", provider, endpoint))
            assertTrue(ContinuityValidator.validate(bytes, trust) is ContinuityValidation.Matched)
        }
    }

    @Test fun endpointHintsRejectEmbeddedCredentialsQueriesAndFragments() {
        val endpoints = listOf(
            "https://user:password@example.com/sync", "https://example.com/sync?token=secret",
            "https://example.com/sync#token", "ftp|host=example.com|password=hunter2|root=sync",
            "s3|endpoint=https://example.com|access_key=secret|root=sync",
            "webdav|endpoint=https://user:secret@example.com|user=name|root=sync",
        )
        for (endpoint in endpoints) {
            val root = fixture.root.changeState("instanceBinding") { rows -> rows.changeRow(0) { it.change("endpoint_hint", JsonPrimitive(endpoint)) } }
            val bytes = fixture.input(root)
            val trust = fixture.testTrust(bytes).copy(repository = fixture.trust.repository.copy(endpointHint = endpoint))
            fixture.rejected(ContinuityValidator.validate(bytes, trust))
        }
    }
}
