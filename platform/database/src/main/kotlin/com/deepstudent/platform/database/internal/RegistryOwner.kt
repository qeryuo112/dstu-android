package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.DatabaseConfigurationException
import com.deepstudent.platform.database.DatabaseLog
import com.deepstudent.platform.database.DatabasePhase
import com.deepstudent.platform.database.DatabaseProfile
import com.deepstudent.platform.database.DatabaseWait
import com.deepstudent.platform.database.MigrationDiagnostic
import com.deepstudent.platform.database.MigrationException
import com.deepstudent.platform.database.MigrationLog
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import java.io.File
import java.util.concurrent.TimeUnit

internal object RegistryOwner {
    private val monitor = Object()
    private val slots = mutableMapOf<String, RegistrySlot>()

    fun openLocal(file: File, profile: DatabaseProfile, log: DatabaseLog): SqlDatabase =
        open(file, profile, null, log, { DatabaseOpener.openLocal(it, profile) })

    fun openShared(
        file: File, database: SharedDatabase, source: MigrationSource,
        profile: DatabaseProfile, log: DatabaseLog, migrationLog: MigrationLog,
    ): SqlDatabase = open(file, profile, database, log, {
        DatabaseOpener.openShared(it, database, source, profile, migrationLog)
    }, { engine -> DatabaseOpener.revalidateShared(engine, database, source, migrationLog) })

    private fun open(
        requested: File, profile: DatabaseProfile, database: SharedDatabase?, log: DatabaseLog,
        create: (File) -> OpenedDatabase, validateReuse: (DatabaseEngine) -> com.deepstudent.platform.database.MigrationReport? = { null },
    ): SqlDatabase = logged(log, DatabasePhase.OPEN) {
        val file = try { requested.canonicalFile } catch (_: Exception) {
            throw MigrationException(MigrationDiagnostic.DATABASE_FAILED)
        }
        val (slot, creator) = reserve(file, profile, database)
        if (creator) createOwner(slot, create)
        try {
            val report = if (creator) slot.engine!!.report else validateReuse(slot.engine!!)
            DatabaseLease(slot.engine!!, profile, log, report) {
                release(slot)
            }
        } catch (failure: Throwable) {
            release(slot)
            throw failure
        }
    }

    private fun reserve(file: File, profile: DatabaseProfile, database: SharedDatabase?): Pair<RegistrySlot, Boolean> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(profile.lockWaitMillis)
        synchronized(monitor) {
            while (true) {
                rejectHardlinkAlias(file)
                val slot = slots[file.path]
                if (slot == null) {
                    val created = RegistrySlot(file, profile, database)
                    slots[file.path] = created
                    return created to true
                }
                if (slot.closeFailed || slot.profile != profile || slot.database != database) {
                    throw DatabaseConfigurationException()
                }
                if (slot.engine != null && !slot.closing) {
                    slot.leases++
                    return slot to false
                }
                awaitMonitor(deadline, DatabaseWait.REGISTRY)
            }
        }
    }

    private fun createOwner(slot: RegistrySlot, create: (File) -> OpenedDatabase) {
        var opened: OpenedDatabase? = null
        try {
            val originalKey = fileKey(slot.file)
            opened = create(slot.file)
            val currentKey = fileKey(slot.file) ?: throw DatabaseConfigurationException()
            if (originalKey != null && originalKey != currentKey) throw DatabaseConfigurationException()
            val identity = DatabaseFileIdentity(slot.file, currentKey)
            val engine = DatabaseEngine(slot.file, slot.profile, opened.session, opened.report, identity)
            synchronized(monitor) { slot.key = currentKey; slot.engine = engine; monitor.notifyAll() }
        } catch (failure: Throwable) {
            try { opened?.session?.close() } catch (_: Exception) { }
            synchronized(monitor) { slots.remove(slot.file.path); monitor.notifyAll() }
            throw failure
        }
    }

    private fun release(slot: RegistrySlot) {
        val shouldClose = synchronized(monitor) {
            slot.leases--
            if (slot.leases == 0) { slot.closing = true; true } else false
        }
        if (!shouldClose) return
        try {
            slot.engine!!.close()
            synchronized(monitor) { slots.remove(slot.file.path); monitor.notifyAll() }
        } catch (failure: Throwable) {
            // A failed native close has unknown ownership; never let a new writer replace it.
            synchronized(monitor) { slot.closeFailed = true; monitor.notifyAll() }
            throw MigrationException(MigrationDiagnostic.DATABASE_FAILED, causeClass = failure.javaClass.name)
        }
    }

    private fun rejectHardlinkAlias(file: File) {
        val candidate = fileKey(file)
        slots.values.forEach { slot ->
            val owned = slot.key ?: fileKey(slot.file)
            if (slot.file.path == file.path) {
                if (slot.key != null && candidate != owned) throw DatabaseConfigurationException()
            } else if (candidate != null && candidate == owned) {
                throw DatabaseConfigurationException()
            }
        }
    }


    internal fun awaitMonitor(deadline: Long, stage: DatabaseWait) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw com.deepstudent.platform.database.DatabaseBusyException(stage)
        try { monitor.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt()) }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.util.concurrent.CancellationException("database wait interrupted")
        }
    }
}

internal class RegistrySlot(val file: File, val profile: DatabaseProfile, val database: SharedDatabase?) {
    var engine: DatabaseEngine? = null
    var key: Any? = null
    var leases: Int = 1
    var closing: Boolean = false
    var closeFailed: Boolean = false
}
