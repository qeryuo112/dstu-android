package com.deepstudent.app.environments

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.deepstudent.engines.proot.PackagedPRootArtifact
import com.deepstudent.features.environments.data.AndroidEnvironmentPlatform
import java.io.File

/** Composition only: all journal, archive and process work belongs to the platform adapters. */
internal object MainEnvironmentFactory {
    fun open(context: Context): OpenedEnvironment {
        val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
        val connectivity = requireNotNull(context.getSystemService(ConnectivityManager::class.java)) {
            "network-state service is unavailable"
        }
        val network = connectivity.activeNetwork
        val dns = network?.let { connectivity.getLinkProperties(it) }?.dnsServers.orEmpty()
            .mapNotNull { it.hostAddress }.distinct()
        val base = File(context.filesDir, "environments")
        val artifact = PackagedPRootArtifact.fromNativeDirectory(nativeDirectory)
        val platform = AndroidEnvironmentPlatform(
            baseDirectory = base,
            runtimeDirectory = File(base, "runtime"),
            executableDirectory = nativeDirectory,
            dnsServers = dns,
            artifact = artifact,
        )
        Log.i("DstuEnvironmentEntry", "event=owner-opened dnsCount=${dns.size} source=packaged-native")
        return OpenedEnvironment(platform.manager(), platform.console(), platform::close)
    }
}
