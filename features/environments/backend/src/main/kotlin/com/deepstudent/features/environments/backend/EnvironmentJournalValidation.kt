package com.deepstudent.features.environments.backend

import com.deepstudent.features.environments.api.EnvironmentTaskId

internal object EnvironmentJournalValidation {
    private val safeToken = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")

    fun requireValid(journal: EnvironmentJournal) {
        requireIdentity(journal)
        requireCheckpoint(journal)
        journal.pendingRecipeId?.let(PackageRecipeCatalog::require)
        requireReceipts(journal)
    }

    private fun requireIdentity(journal: EnvironmentJournal) {
        listOf(journal.rootfsId, journal.generationId, journal.activeGeneration).filterNotNull().forEach {
            require(it.matches(safeToken)) { "invalid rootfs identity token" }
        }
        journal.rootfsSha256?.let { require(it.matches(Regex("[0-9a-f]{64}"))) { "invalid rootfs hash" } }
        journal.lastTaskId?.let(::EnvironmentTaskId)
    }

    private fun requireCheckpoint(journal: EnvironmentJournal) {
        if (journal.checkpoint != EnvironmentCheckpoint.VERIFY) {
            require(journal.rootfsId != null && journal.rootfsSha256 != null && journal.generationId != null) {
                "incomplete journal identity"
            }
        }
        if (journal.checkpoint == EnvironmentCheckpoint.DONE) {
            require(journal.activeGeneration == journal.generationId && journal.activeGeneration != null) { "invalid completed journal" }
        }
    }

    private fun requireReceipts(journal: EnvironmentJournal) {
        journal.receipts.forEach { (id, receipt) ->
            require(id == receipt.recipeId) { "receipt identity mismatch" }
            PackageReceiptPolicy.requireValid(PackageRecipeCatalog.require(id), receipt)
        }
    }
}
