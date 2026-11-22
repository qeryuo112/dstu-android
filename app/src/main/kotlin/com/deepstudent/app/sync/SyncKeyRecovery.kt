package com.deepstudent.app.sync

import com.deepstudent.platform.security.CredentialStoreException
import com.deepstudent.platform.security.MasterKeyFailure
import com.deepstudent.platform.security.SecurityPorts
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.PairingKeyRecovery
import com.deepstudent.services.sync.backend.SyncFailure

internal class SyncKeyRecovery(private val security: SecurityPorts) : PairingKeyRecovery {
    override fun requireUsable() = securityOperation(security::requireMasterKeyUsable)
    override fun rebuildLostKey() = securityOperation(security::rebuildLostMasterKey)

    private fun securityOperation(operation: () -> Unit) = try {
        operation()
    } catch (failure: CredentialStoreException) {
        val error = if (failure.masterKeyFailure == MasterKeyFailure.PERMANENTLY_LOST) SyncError.CREDENTIAL_KEY_LOST
        else SyncError.CREDENTIAL_UNAVAILABLE
        throw SyncFailure(error)
    }
}
