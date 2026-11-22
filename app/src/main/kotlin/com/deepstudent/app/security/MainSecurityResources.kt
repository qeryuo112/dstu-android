package com.deepstudent.app.security

import android.content.Context
import android.util.Log
import com.deepstudent.platform.security.CredentialStoreError
import com.deepstudent.platform.security.CredentialStoreException
import com.deepstudent.platform.security.DsbkException
import com.deepstudent.platform.security.SecurityAudit
import com.deepstudent.platform.security.SecurityFactory
import com.deepstudent.platform.security.SecurityPorts

/** Composition only: the application owns the returned ports and their lifetime. */
internal object MainSecurityResources {
    private val audit = SecurityAudit { event ->
        // SecurityAuditEvent renders only its validated fixed fields.
        Log.i("DstuSecurity", event.toString())
    }

    fun open(context: Context): SecurityPorts = open(context) { supplied, audit ->
        SecurityFactory.create(supplied, audit)
    }

    /** Allows device tests to use isolated directories and a non-production Keystore alias. */
    internal fun open(
        context: Context,
        factory: (Context, SecurityAudit) -> SecurityPorts,
    ): SecurityPorts = try {
        factory(context, audit)
    } catch (error: CredentialStoreException) {
        throw error
    } catch (error: DsbkException) {
        throw error
    } catch (_: Exception) {
        // Context/path failures can contain user data; never expose their messages or causes.
        throw CredentialStoreException(CredentialStoreError.STORAGE_UNAVAILABLE)
    }
}
