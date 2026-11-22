package com.deepstudent.platform.storage

import java.io.File

/** File names in this private directory are the only accepted metadata vocabulary. */
internal class DataSpaceLayout(private val root: File) {
    private val control = File(root, ".dataspace")
    val lock = File(control, "lock")
    private val slots = File(control, "slots")
    private val pointer = File(control, "active")
    private val pointerTemp = File(control, "active.tmp")
    private val initialized = File(control, "initialized")
    private val initializedTemp = File(control, "initialized.tmp")

    fun ensureLockParent() {
        DurableFiles.directory(root)
        DurableFiles.createDirectory(control)
        DurableFiles.createDirectory(slots)
    }

    fun ready(): Boolean {
        checkLayout()
        val hasPointer = DurableFiles.exists(pointer)
        val hasInitialized = DurableFiles.exists(initialized)
        if (hasInitialized) {
            checkConstant(initialized, DataSpaceState.INITIALIZED)
            if (!hasPointer) throw DataSpaceException(DataSpaceError.POINTER_MISSING)
            return true
        }
        return false
    }

    /** Must hold the exclusive lock. The original active directory is never moved. */
    fun initialize() {
        if (ready()) return
        if (DurableFiles.children(slots).isNotEmpty()) throw DataSpaceException(DataSpaceError.POINTER_MISSING)
        if (DurableFiles.exists(pointer)) {
            if (readPointer() != DataSpaceState.LEGACY) throw DataSpaceException(DataSpaceError.CORRUPT_STATE)
            DurableFiles.directory(File(root, "active"))
        } else {
            DurableFiles.createDirectory(File(root, "active"))
            DurableFiles.writeAtomic(pointer, pointerTemp, DataSpaceState.encode(DataSpaceState.LEGACY))
        }
        DurableFiles.writeAtomic(initialized, initializedTemp, DataSpaceState.INITIALIZED.toByteArray())
    }

    fun current(): String {
        if (!ready()) throw DataSpaceException(DataSpaceError.CORRUPT_STATE)
        val slot = readPointer()
        val active = rootFor(slot)
        if (!DurableFiles.exists(active)) throw DataSpaceException(DataSpaceError.CORRUPT_STATE)
        DurableFiles.directory(active)
        return slot
    }

    fun rootFor(slot: String): File = if (slot == DataSpaceState.LEGACY) {
        File(root, "active")
    } else {
        File(slotDir(slot), "data")
    }

    fun prepare(slot: String, current: String) {
        if (!DataSpaceState.validPhysicalSlot(slot)) throw DataSpaceException(DataSpaceError.CORRUPT_STATE)
        val dir = slotDir(slot)
        DurableFiles.createDirectory(dir)
        checkSlot(dir)
        val data = File(dir, "data")
        if (!DurableFiles.exists(data) && (slot == current || isCommitted(slot))) {
            throw DataSpaceException(DataSpaceError.CORRUPT_STATE)
        }
        DurableFiles.createDirectory(data)
    }

    fun status(slot: String, current: String): StagingStatus = when {
        slot == current -> StagingStatus.ACTIVE
        isCommitted(slot) -> StagingStatus.PREVIOUSLY_ACTIVATED
        else -> StagingStatus.WRITABLE
    }

    fun isCommitted(slot: String): Boolean {
        val marker = File(slotDir(slot), "committed")
        if (!DurableFiles.exists(marker)) return false
        checkConstant(marker, DataSpaceState.COMMITTED)
        return true
    }

    fun ensureCommitted(slot: String) {
        if (slot == DataSpaceState.LEGACY || isCommitted(slot)) return
        val dir = slotDir(slot)
        DurableFiles.directory(dir)
        DurableFiles.writeAtomic(File(dir, "committed"), File(dir, "committed.tmp"), DataSpaceState.COMMITTED.toByteArray())
    }

    fun syncStaging(slot: String) {
        val dir = slotDir(slot)
        val data = rootFor(slot)
        if (DurableFiles.children(data).isEmpty()) throw DataSpaceException(DataSpaceError.INCOMPLETE_STAGING)
        DurableFiles.syncTree(data)
        DurableFiles.syncDirectory(dir)
        DurableFiles.syncDirectory(slots)
    }

    fun switchPointer(slot: String, checkpoint: (DataSpacePhase) -> Unit) {
        DurableFiles.writeTemp(pointerTemp, DataSpaceState.encode(slot))
        checkpoint(DataSpacePhase.POINTER_TEMP_DURABLE)
        DurableFiles.replace(pointerTemp, pointer)
        checkpoint(DataSpacePhase.POINTER_REPLACED)
        DurableFiles.syncDirectory(control)
        checkpoint(DataSpacePhase.POINTER_DURABLE)
    }

    fun discard(slot: String) {
        val dir = slotDir(slot)
        if (DurableFiles.exists(dir)) {
            checkSlot(dir)
            DurableFiles.deleteTree(dir)
        }
    }

    private fun readPointer(): String = DataSpaceState.decode(DurableFiles.readBounded(pointer))
    private fun slotDir(slot: String): File {
        if (!DataSpaceState.validPhysicalSlot(slot)) throw DataSpaceException(DataSpaceError.CORRUPT_STATE)
        return File(slots, slot)
    }

    private fun checkLayout() {
        DurableFiles.directory(control)
        DurableFiles.directory(slots)
        val names = setOf("lock", "slots", "active", "active.tmp", "initialized", "initialized.tmp")
        DurableFiles.children(control).forEach { entry ->
            if (entry.name !in names) throw DataSpaceException(DataSpaceError.UNKNOWN_ENTRY)
            if (entry.name == "slots") DurableFiles.directory(entry) else DurableFiles.regular(entry)
        }
        DurableFiles.children(slots).forEach { dir ->
            if (!DataSpaceState.validPhysicalSlot(dir.name)) throw DataSpaceException(DataSpaceError.UNKNOWN_ENTRY)
            checkSlot(dir)
        }
    }

    private fun checkSlot(dir: File) {
        DurableFiles.directory(dir)
        val names = setOf("data", "committed", "committed.tmp")
        DurableFiles.children(dir).forEach { entry ->
            if (entry.name !in names) throw DataSpaceException(DataSpaceError.UNKNOWN_ENTRY)
            if (entry.name == "data") DurableFiles.directory(entry) else DurableFiles.regular(entry)
        }
    }

    private fun checkConstant(file: File, text: String) {
        if (!DurableFiles.readBounded(file).contentEquals(text.toByteArray())) {
            throw DataSpaceException(DataSpaceError.CORRUPT_STATE)
        }
    }
}
