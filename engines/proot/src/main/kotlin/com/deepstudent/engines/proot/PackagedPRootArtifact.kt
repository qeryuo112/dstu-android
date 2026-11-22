package com.deepstudent.engines.proot

import java.io.File
import java.util.Properties

/** Only source-built APK artifacts are accepted by the production composition. */
object PackagedPRootArtifact {
    fun fromNativeDirectory(directory: File): PRootArtifact {
        val properties = Properties()
        val resource = requireNotNull(javaClass.getResourceAsStream("/dstu-proot-artifacts.properties")) {
            "source-built PRoot manifest is missing"
        }
        resource.use(properties::load)
        return PRootArtifact(
            manifest = PRootArtifactManifest(
                id = properties.getProperty("id"),
                binarySha256 = properties.getProperty("libdstu_proot.so.sha256"),
                binaryBytes = properties.getProperty("libdstu_proot.so.bytes").toLong(),
                loaderSha256 = properties.getProperty("libdstu_loader.so.sha256"),
                loaderBytes = properties.getProperty("libdstu_loader.so.bytes").toLong(),
                minSdk = 26,
            ),
            binary = File(directory, "libdstu_proot.so"),
            loader = File(directory, "libdstu_loader.so"),
            provenance = PRootProvenance.SOURCE_LOCKED,
        )
    }
}
