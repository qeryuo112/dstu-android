package com.deepstudent.app

import android.app.Application
import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.deepstudent.app.backup.BackupRestoreCoordinator
import com.deepstudent.app.environments.LazyEnvironmentOwner
import com.deepstudent.app.environments.MainEnvironmentFactory
import com.deepstudent.app.execution.LazyExecutionOwner
import com.deepstudent.app.execution.mainShellRouter
import com.deepstudent.app.graph.AndroidGraphLogger
import com.deepstudent.app.graph.AndroidProcessIdentityResolver
import com.deepstudent.app.graph.AppGraph
import com.deepstudent.app.graph.AppGraphFactory
import com.deepstudent.app.graph.EngineGraph
import com.deepstudent.app.graph.IsolatedGraph
import com.deepstudent.app.graph.LazyProcessOwner
import com.deepstudent.app.graph.MainGraph
import com.deepstudent.app.graph.MainResourceFactory
import com.deepstudent.app.graph.MigrationRetryOwner
import com.deepstudent.app.graph.ProcessIdentity
import com.deepstudent.app.graph.ProcessRole
import com.deepstudent.app.runtime.ForegroundRuntimePorts
import com.deepstudent.app.runtime.MainRuntimeResources
import com.deepstudent.app.runtime.NoteImportActions
import com.deepstudent.app.scheduling.mainWorkManagerConfiguration
import com.deepstudent.app.scheduling.schedulePanelIntent
import com.deepstudent.app.security.MainSecurityResources
import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.app.storage.MainDataSpaceAccess
import com.deepstudent.app.storage.MainStorageResources
import com.deepstudent.app.sync.LazySyncOwner
import com.deepstudent.app.sync.MainSyncDatabaseSources
import com.deepstudent.app.sync.MainSyncFactory
import com.deepstudent.app.sync.MainSyncResources
import com.deepstudent.app.terminal.MainTerminalResources
import com.deepstudent.app.workspace.MainWorkspaceOwner
import com.deepstudent.app.workspace.WorkspacePorts
import com.deepstudent.app.workspace.WorkspaceRuntimeTools
import com.deepstudent.app.workspace.WorkspaceToolPorts
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.features.chat.backend.application.RuntimeChatStreams
import com.deepstudent.features.environments.api.EnvironmentManager
import com.deepstudent.features.sync.ui.SyncController
import com.deepstudent.features.terminal.api.TerminalSessions
import com.deepstudent.platform.background.DutyRegistry
import com.deepstudent.platform.background.ForegroundDuty
import com.deepstudent.platform.background.ForegroundDutyStarter
import com.deepstudent.platform.background.ForegroundTaskPorts
import com.deepstudent.platform.background.HostForegroundDutyProjector
import com.deepstudent.platform.background.RuntimeForegroundHost
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.execution.ShizukuExecutionFactory
import com.deepstudent.platform.security.SecurityPorts
import com.deepstudent.services.execution.api.ExecutionSystem
import com.deepstudent.services.runtime.api.RuntimeProjectionChange
import com.deepstudent.services.runtime.api.RuntimeProjectionResult
import com.deepstudent.services.runtime.api.RuntimeRecordKey
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeTaskGeneration
import com.deepstudent.services.sync.backend.RepositoryEncryption
import com.deepstudent.services.sync.data.transfer.DsbkSyncCryptography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Application-scoped composition owner.
 *
 * Android creates one Application object per process, so [appGraph] is unique
 * per process and survives Activity recreation. No business database, task
 * manager or runtime service is opened synchronously during graph construction.
 */
class DstuApplication :
    Application(),
    RuntimeForegroundHost,
    androidx.work.Configuration.Provider,
    com.deepstudent.platform.background.scheduling.RuntimeSchedulingHost {
    private val runtimeLock = Mutex()

    @Volatile private var runtimeResources: MainRuntimeResources? = null
    private var startupScope: CoroutineScope? = null
    private val workspaceOwner = MainWorkspaceOwner()
    internal val backupRestore by lazy {
        BackupRestoreCoordinator(
            this,
            com.deepstudent.app.backup.OrdinaryRestoreIdentity(this),
            com.deepstudent.app.backup.ContinuityQueuedRestore(this, com.deepstudent.app.backup.ManagedContinuityRestore(this)),
        )
    }
    internal val continuityBackups by lazy { com.deepstudent.app.backup.SafContinuityEntryActions(this, backupRestore) }
    internal val encryptedBackups by lazy {
        com.deepstudent.app.backup.SafEncryptedBackupActions(
            this,
            com.deepstudent.app.backup.EncryptedBackupScratch(cacheDir),
            backupRestore,
            ::security,
        )
    }
    private val dataSpaceAccess = lazy {
        backupRestore.beforeBusinessOpen()
        MainDataSpaceAccess(this)
    }
    private val migrationRetryOwner by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MigrationRetryOwner(checkNotNull(startupScope), { dataSpaceAccess.value.migration.query() }) {
            dataSpaceAccess.value.migration.retry()
        }
    }
    internal val migrationRetryState get() = migrationRetryOwner.state
    private val terminalResidencyOwner = lazy {
        com.deepstudent.app.terminal.TerminalResidency(
            foregroundScope,
            com.deepstudent.app.power.BackgroundPreferences(this),
            foregroundDuties,
            ForegroundDutyStarter(this, this),
            com.deepstudent.platform.background.androidWakeLockController(this, AndroidOperationLogger),
        )
    }
    internal val terminalResidency get() = terminalResidencyOwner.value
    private val residentTerminal by lazy { terminalResidency.attach(terminalOwner.value.sessions) }
    private val terminalOwner = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        check(processIdentity.role == ProcessRole.MAIN) {
            "Terminal owner requested in ${processIdentity.role} process"
        }
        MainTerminalResources.open(this)
    }
    private val environmentOwner = LazyEnvironmentOwner {
        check(processIdentity.role == ProcessRole.MAIN) { "Environment owner requires the main process" }
        MainEnvironmentFactory.open(this)
    }
    internal val environmentResourcesInitialized: Boolean get() = environmentOwner.isInitialized
    private val executionOwner = LazyExecutionOwner(
        processRole = { processIdentity.role },
        logger = AndroidOperationLogger,
    ) { ShizukuExecutionFactory.create(applicationContext) }
    internal val executionResourcesInitialized: Boolean get() = executionOwner.isInitialized

    private val securityOwner = LazyProcessOwner("security", AndroidGraphLogger) {
        check(processIdentity.role == ProcessRole.MAIN) { "Security owner requires the main process" }
        MainSecurityResources.open(this)
    }
    internal val securityResourcesInitialized: Boolean get() = securityOwner.isInitialized
    private val syncOwner = LazySyncOwner({ processIdentity.role }) {
        backupRestore.requireStableSyncIdentity()
        val directory = File(noBackupFilesDir.canonicalFile, "sync")
        val security = security()
        val encryption = RepositoryEncryption(DsbkSyncCryptography(security.dsbk))
        val factory = MainSyncFactory(encryption, BundledMigrationSource(this))
        // Only the disposable, separately packaged benchmark variant talks to its loopback fixture.
        val isolatedBenchmark = BuildConfig.APPLICATION_ID == "com.deepstudent.app.benchmark"
        MainSyncResources.open(
            directory,
            security,
            allowLoopbackHttp = isolatedBenchmark,
            encryption = encryption,
            transferFactory = { store, providers, scope ->
                val storage = storage()
                factory.open(
                    storage.databaseForSync(),
                    storage.activeRoot.resolve("vfs_blobs"),
                    store,
                    providers,
                    scope,
                    directory,
                    MainSyncDatabaseSources(storage::incomingDecksDatabaseForSync, storage::allDatabasesForSync),
                )
            },
        )
    }
    internal val syncResourcesInitialized: Boolean get() = syncOwner.isInitialized

    override val foregroundDuties = DutyRegistry(HostForegroundDutyProjector({ foregroundScope }, this, AndroidOperationLogger))
    override val foregroundTasks: ForegroundTaskPorts = ForegroundRuntimePorts { runtime() }.ports
    override val foregroundScope: CoroutineScope get() = checkNotNull(startupScope)
    override val foregroundLogger: Logger get() = AndroidOperationLogger

    internal val schedules by lazy {
        check(processIdentity.role == ProcessRole.MAIN)
        com.deepstudent.app.scheduling.MainSchedules(this, ::runtime)
    }
    override val workManagerConfiguration: androidx.work.Configuration
        get() = mainWorkManagerConfiguration(processIdentity.role == ProcessRole.MAIN)
    override val schedulingScope: CoroutineScope get() = foregroundScope
    override val schedulingLogger: Logger get() = AndroidOperationLogger

    override suspend fun runScheduledWork(request: com.deepstudent.platform.background.scheduling.ScheduledWorkRequest) =
        schedules.runWorker(request)

    override suspend fun dispatchReminder(
        fire: com.deepstudent.platform.background.scheduling.ReminderFire,
        deliver: (com.deepstudent.platform.background.scheduling.ReminderDispatch) -> Boolean,
    ) = schedules.dispatchReminder(fire, deliver)

    override suspend fun replanSchedules(reason: com.deepstudent.platform.background.scheduling.ReplanReason) =
        schedules.replanAll(reason.name.lowercase())

    override fun reminderActivityIntent(fire: com.deepstudent.platform.background.scheduling.ReminderFire) = schedulePanelIntent()

    internal val noteImport by lazy {
        NoteImportActions(::runtime, ForegroundDutyStarter(this, this), BuildConfig.APPLICATION_ID == "com.deepstudent.app.s2device")
    }

    override fun isDataSyncForegroundTask(snapshot: RuntimeSnapshot): Boolean = snapshot.kind == MainRuntimeResources.NOTE_IMPORT &&
        snapshot.ownership.ownerId != null && snapshot.ownership.ownerId == runtimeResources?.ownerId &&
        !snapshot.execution.cancelRequested && snapshot.state == com.deepstudent.services.runtime.api.RuntimeState.RUNNING

    override suspend fun reconcileBackgroundResidency() {
        // Await the sole runtime's startup reconciliation before the service validates its live references.
        runtime()
    }

    override fun isBackgroundStickyOptedIn(): Boolean = terminalResidencyOwner.isInitialized() && terminalResidency.enabled.value

    override fun isContinuousDutyEligible(duty: com.deepstudent.platform.background.ContinuousDuty): Boolean =
        terminalResidencyOwner.isInitialized() && terminalResidency.eligible(duty)

    override suspend fun disableBackground() {
        terminalResidency.setEnabled(false)
    }

    override fun onForegroundServiceDestroyed() {
        if (terminalResidencyOwner.isInitialized()) terminalResidency.releasePower()
    }

    override suspend fun checkpointForegroundTask(duty: ForegroundDuty) {
        runtime().supervisor.requestCheckpoint(duty.taskId, duty.generation)
    }

    override suspend fun registerForegroundDuty(duty: ForegroundDuty, recordKey: RuntimeRecordKey): RuntimeProjectionResult =
        foregroundProjection(duty, RuntimeProjectionChange.RegisterDuty(recordKey, RuntimeRecordKey.parse(duty.purpose.name)))

    override suspend fun releaseForegroundDuty(duty: ForegroundDuty, recordKey: RuntimeRecordKey): RuntimeProjectionResult =
        foregroundProjection(duty, RuntimeProjectionChange.ReleaseDuty(recordKey))

    private suspend fun foregroundProjection(duty: ForegroundDuty, change: RuntimeProjectionChange): RuntimeProjectionResult =
        runtime().ledger.apply(RuntimeTaskGeneration(duty.taskId, duty.generation), change, SystemWallClock.now())

    lateinit var processIdentity: ProcessIdentity
        private set

    internal lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        val startedAt = SystemClock.elapsedRealtime()
        processIdentity = AndroidProcessIdentityResolver.resolve(this)
        if (processIdentity.role == ProcessRole.MAIN) {
            runCatching { com.deepstudent.app.diagnostics.S231Diagnostics.initialize(noBackupFilesDir) }
        }
        val storageFactory = if (processIdentity.role == ProcessRole.MAIN) {
            MainResourceFactory { MainStorageResources.openManaged(this, dataSpaceAccess.value) }
        } else {
            null
        }
        appGraph = AppGraphFactory(mainResourceFactory = storageFactory).create(processIdentity)
        AndroidGraphLogger.info(
            "application.created",
            mapOf(
                "processName" to processIdentity.processName,
                "processNameSource" to processIdentity.source,
                "role" to processIdentity.role,
                "graphId" to appGraph.graphId,
                "pid" to Process.myPid(),
                "uid" to Process.myUid(),
                "businessResourcesInitialized" to appGraph.businessResourcesInitialized,
                "elapsedMs" to (SystemClock.elapsedRealtime() - startedAt),
            ),
        )
        if (processIdentity.role == ProcessRole.MAIN) {
            com.deepstudent.app.runtime.ScheduledNoteExecution.configureFixture(this)
            startRuntimeRecovery()
        }
    }

    val mainGraph: MainGraph
        get() = appGraph as? MainGraph
            ?: throw IllegalStateException(
                "MainGraph requested in ${processIdentity.role} process " +
                    "'${processIdentity.processName}'",
            )

    internal suspend fun storage(): MainStorageResources = withContext(Dispatchers.IO) {
        mainGraph.mainProcessResources() as MainStorageResources
    }

    internal fun retryMigration() = migrationRetryOwner.start()

    internal suspend fun workspace(): WorkspacePorts = withContext(Dispatchers.IO) {
        val resources = runtime()
        val runtime = resources.controller
        workspaceOwner.open(
            this@DstuApplication,
            storage().notes,
            RuntimeChatStreams(runtime, runtime, runtime),
            WorkspaceToolPorts(
                terminal = terminal(),
                environments = { environments() },
                execution = { execution() },
                sync = { sync() },
                documents = com.deepstudent.app.storage.documents.WorkspaceDocumentPrivacy(this@DstuApplication, storage().workspaceFiles),
                runtime = WorkspaceRuntimeTools(
                    noteImport = noteImport,
                    backgroundPower = terminalResidency,
                    schedules = schedules,
                    approvals = resources.capabilities,
                    shell = resources.shell,
                ),
            ),
        )
    }

    internal suspend fun environments(): EnvironmentManager = withContext(Dispatchers.IO) {
        check(processIdentity.role == ProcessRole.MAIN) { "Environments require the main process" }
        environmentOwner.get()
    }

    internal suspend fun shutdownEnvironments() = withContext(Dispatchers.IO + NonCancellable) {
        environmentOwner.close()
    }

    internal suspend fun execution(): ExecutionSystem = withContext(Dispatchers.IO) {
        check(processIdentity.role == ProcessRole.MAIN) { "Execution requires the main process" }
        executionOwner.get()
    }

    internal suspend fun shutdownExecution() = withContext(Dispatchers.IO + NonCancellable) {
        executionOwner.close()
    }

    /** Only the composition root passes individual security ports to dependent services. */
    internal suspend fun security(): SecurityPorts = withContext(Dispatchers.IO) {
        check(processIdentity.role == ProcessRole.MAIN) { "Security requires the main process" }
        securityOwner.get()
    }

    internal suspend fun sync(): SyncController = withContext(Dispatchers.IO) {
        syncOwner.get().controller
    }

    internal suspend fun terminal(): TerminalSessions = withContext(Dispatchers.IO) {
        check(processIdentity.role == ProcessRole.MAIN) {
            "Terminal requested in ${processIdentity.role} process"
        }
        residentTerminal
    }

    internal suspend fun shutdownTerminal() = withContext(Dispatchers.IO + NonCancellable) {
        if (terminalOwner.isInitialized()) terminalOwner.value.shutdown()
    }

    internal suspend fun runtime(): MainRuntimeResources = withContext(Dispatchers.IO) {
        runtimeLock.withLock {
            runtimeResources ?: openRuntimeResources().prepare().also { runtimeResources = it }
        }
    }

    private suspend fun openRuntimeResources(): MainRuntimeResources = storage().let {
        MainRuntimeResources.open(it.activeRoot, it, mainShellRouter(this, environmentOwner::console, ::execution))
    }

    private fun startRuntimeRecovery() {
        startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope ->
            scope.launch {
                try {
                    runtime()
                    AndroidGraphLogger.info("runtime.startup", mapOf("state" to "reconciled"))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    AndroidGraphLogger.warn("runtime.startup.failed", failure)
                }
            }
        }
    }

    internal val engineGraph: EngineGraph
        get() = appGraph as? EngineGraph
            ?: throw IllegalStateException(
                "EngineGraph requested in ${processIdentity.role} process " +
                    "'${processIdentity.processName}'",
            )

    internal val isolatedGraph: IsolatedGraph
        get() = appGraph as? IsolatedGraph
            ?: throw IllegalStateException(
                "IsolatedGraph requested in ${processIdentity.role} process " +
                    "'${processIdentity.processName}'",
            )

    override fun onTerminate() {
        startupScope?.cancel()
        kotlinx.coroutines.runBlocking { syncOwner.close() }
        securityOwner.close()
        runtimeResources?.close()
        if (terminalOwner.isInitialized()) terminalOwner.value.close()
        kotlinx.coroutines.runBlocking { shutdownEnvironments() }
        kotlinx.coroutines.runBlocking { shutdownExecution() }
        if (::appGraph.isInitialized) {
            appGraph.close()
        }
        if (dataSpaceAccess.isInitialized()) dataSpaceAccess.value.close()
        super.onTerminate()
    }

    companion object {
        fun from(context: Context): DstuApplication = context.applicationContext as? DstuApplication
            ?: throw IllegalStateException(
                "Application is not DstuApplication: ${context.applicationContext?.javaClass}",
            )
    }
}
