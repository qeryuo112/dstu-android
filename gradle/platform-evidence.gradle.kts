import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

tasks.register("writePlatformEvidence") {
    group = "verification"
    description = "Measures the actual resolved Android AAR manifests against the frozen SDK contract."
    val output = layout.buildDirectory.file("reports/platform/resolved-aars.json")
    outputs.file(output)
    outputs.upToDateWhen { false }
    doLast {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val artifacts = configurations.getByName("debugRuntimeClasspath").incoming.artifactView {
            componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
        }.artifacts.artifacts
        val rows = artifacts.filter { it.file.extension == "aar" }.sortedBy { it.id.componentIdentifier.displayName }.map { artifact ->
            val minSdk = ZipFile(artifact.file).use { archive ->
                val manifest = checkNotNull(archive.getEntry("AndroidManifest.xml")) { "Missing AAR manifest: ${artifact.file}" }
                archive.getInputStream(manifest).use { stream ->
                    val document = factory.newDocumentBuilder().parse(stream)
                    val sdk = document.getElementsByTagName("uses-sdk").item(0) as? org.w3c.dom.Element
                    sdk?.getAttributeNS("http://schemas.android.com/apk/res/android", "minSdkVersion")?.takeIf { it.isNotEmpty() }?.toInt() ?: 1
                }
            }
            check(minSdk <= 26) { "DSTU_AAR_MIN_SDK_EXCEEDS_CONTRACT ${artifact.id.componentIdentifier}: $minSdk > 26" }
            val digest = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes()).joinToString("") { "%02x".format(it) }
            mapOf("coordinate" to artifact.id.componentIdentifier.displayName, "sha256" to digest, "minSdk" to minSdk)
        }
        check(rows.isNotEmpty()) { "No actual Android AAR dependencies resolved" }
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(mapOf("unit" to "S1.01", "aars" to rows))) + "\n")
        }
        logger.lifecycle("[dstu.platform] event=resolved-aars.verified count=${rows.size} minSdk=26 compileSdk=36")
    }
}
