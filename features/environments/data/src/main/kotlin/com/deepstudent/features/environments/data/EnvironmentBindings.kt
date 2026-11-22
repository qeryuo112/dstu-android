package com.deepstudent.features.environments.data

import com.deepstudent.engines.proot.PRootBindMount

internal object EnvironmentBindings {
    fun ensureResolvConf(paths: EnvironmentPaths) {
        paths.runtime.mkdirs()
        val servers = paths.dnsServers.distinct()
        require(servers.all { it.matches(Regex("[0-9A-Fa-f:.%a-zA-Z0-9_-]+")) }) {
            "invalid DNS address"
        }
        // Use the active Android network, not hard-coded public resolvers. An offline
        // health probe needs no DNS; package installation reports its actual network error.
        if (servers.isNotEmpty()) {
            paths.resolvConf.writeText(servers.joinToString("") { "nameserver $it\n" }, Charsets.UTF_8)
        }
        if (!paths.fipsEnabled.isFile) paths.fipsEnabled.writeText("0\n", Charsets.UTF_8)
    }

    fun binds(paths: EnvironmentPaths): List<PRootBindMount> {
        ensureResolvConf(paths)
        return buildList {
            add(PRootBindMount("/dev", "/dev"))
            add(PRootBindMount("/proc", "/proc"))
            add(PRootBindMount("/sys", "/sys"))
            if (paths.dnsServers.isNotEmpty()) {
                add(PRootBindMount(paths.resolvConf.absolutePath, "/etc/resolv.conf"))
            }
            // gcrypt probes this Linux proc node, which Android denies to APP UIDs.
            // This is explicit guest sysdata, not a claim about the host FIPS state.
            add(PRootBindMount(paths.fipsEnabled.absolutePath, "/proc/sys/crypto/fips_enabled"))
        }
    }
}
