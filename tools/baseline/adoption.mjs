import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { artifact, git, log, main, root, sha256 } from './lib.mjs';

const unit = 'S0.13';
const file = 'config/operit-adoption.json';
const planFile = 'docs/plans/2026-11-15-mobile-full-feature-refactor.md';
const brandFile = 'config/design/brand.baseline.json';
const domainsFile = 'config/baseline/domains.json';
const workbenchFile = 'config/baseline/workbench.json';
const classificationFile = 'config/baseline/classification.json';
const lockFile = 'config/source-lock.json';
const terminalKernelFile = 'terminal/src/main/java/com/ai/assistance/operit/terminal/TerminalManager.kt';
const externalPackFile = 'app/src/main/java/com/ai/assistance/operit/core/tools/system/OperitTerminalManager.kt';
const operitRoot = resolve(root, '..', 'Operit');
const terminalRoot = resolve(operitRoot, 'terminal');
const desktopRoot = resolve(root, '..', 'dstu-node');
const read = path => readFileSync(resolve(operitRoot, path), 'utf8');
const exists = path => existsSync(resolve(operitRoot, path));
const readDesktop = path => readFileSync(resolve(desktopRoot, path), 'utf8');
const desktopExists = path => existsSync(resolve(desktopRoot, path));

const section31 = [
  ['terminal-kernel', '终端内核', 'terminal/src/main/java/com/ai/assistance/operit/terminal/', 'direct-port', 'S1.20', 'S6.02', ['S6.01', 'S6.04', 'S6.05', 'S6.07']],
  ['terminal-ui', '终端界面', 'terminal/src/main/java/com/ai/assistance/operit/terminal/', 'direct-port', 'S1.20', 'S6.03', ['S5.06']],
  ['dependency-mirror', '依赖与镜像', 'terminal/src/main/java/com/ai/assistance/operit/terminal/ui/SetupScreen.kt', 'adapter', 'S1.21', 'S6.11', ['S6.09', 'S6.12', 'S6.16']],
  ['shizuku', 'Shizuku', 'app/src/main/java/com/ai/assistance/operit/core/tools/system/ShizukuAuthorizer.kt', 'direct-port', 'S1.22', 'S6.19', ['S6.18', 'S6.23']],
  ['root-standard-exec', 'Root/标准执行', 'app/src/main/java/com/ai/assistance/operit/core/tools/system/AndroidShellExecutor.kt', 'direct-port', 'S2.24', 'S6.21', ['S6.20']],
  ['foreground-service', '前台服务', 'app/src/main/java/com/ai/assistance/operit/api/chat/AIForegroundService.kt', 'adapter', 'S1.09', 'S2.20', ['S2.21']],
  ['floating-window', '悬浮窗', 'app/src/main/java/com/ai/assistance/operit/services/FloatingChatService.kt', 'direct-port', 'S6.40', 'S6.40', ['S2.20']],
  ['quickjs', 'QuickJS', 'quickjs/', 'direct-port', 'S6.31', 'S6.31', ['S6.32']],
  ['toolpkg', 'ToolPkg', 'app/src/main/java/com/ai/assistance/operit/core/tools/packTool/', 'direct-port', 'S6.33', 'S6.33', ['S6.35']],
  ['compose-dsl', 'Compose 扩展', 'app/src/main/java/com/ai/assistance/operit/ui/common/composedsl/', 'direct-port', 'S6.34', 'S6.34', ['S6.44']],
  ['mcp', 'MCP', 'app/src/main/java/com/ai/assistance/operit/core/tools/mcp/McpRuntimeSession.kt', 'adapter', 'S6.28', 'S6.28', ['S6.30']],
  ['workflow', '工作流', 'app/src/main/java/com/ai/assistance/operit/core/workflow/', 'direct-port', 'S3.25', 'S3.25', ['S3.25']],
];
const section32 = [
  ['browser', '原生浏览器', 'app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/websession/browser/', 'direct-port', 'S6.37', 'S6.37', ['S6.38']],
  ['file-provider', '文件 Provider', 'app/src/main/java/com/ai/assistance/operit/provider/WorkspaceDocumentsProvider.kt', 'adapter', 'S2.25', 'S2.25', ['S6.39']],
  ['navigation', '手机/平板导航', 'app/src/main/java/com/ai/assistance/operit/ui/main/layout/', 'reimplement', 'S5.05', 'S5.05', ['S5.06']],
  ['stream-markdown', '流式内容', 'app/src/main/java/com/ai/assistance/operit/ui/common/markdown/StreamMarkdownRenderer.kt', 'direct-port', 'S5.19', 'S5.19', ['S5.17']],
  ['voice', '语音', 'app/src/main/java/com/ai/assistance/operit/api/voice/VoiceServiceFactory.kt', 'adapter', 'S4.19', 'S4.19', ['S4.20']],
  ['doc-ocr', '文档/OCR', 'app/src/main/java/com/ai/assistance/operit/util/OCRUtils.kt', 'adapter', 'S4.11', 'S4.11', ['S4.07']],
  ['local-inference', '本地推理/嵌入', 'llm/llama/', 'direct-port', 'S4.21', 'S4.21', ['S4.22']],
  ['shower', '虚拟屏/设备Agent', 'showerclient/', 'direct-port', 'S6.41', 'S6.41', ['S6.42']],
  ['integrations', 'ToolPkg外部集成', 'app/src/main/java/com/ai/assistance/operit/integrations/', 'adapter', 'S6.36', 'S6.36', ['S7.04']],
];

const evidence = {
  'terminal-kernel': [
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/TerminalManager.kt', line: 47, symbol: 'class TerminalManager', token: 'class TerminalManager' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/SessionManager.kt', line: 16, symbol: 'class SessionManager', token: 'class SessionManager' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/SessionInputRouter.kt', line: 33, symbol: 'object SessionInputRouter', token: 'object SessionInputRouter' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/Pty.kt', line: 11, symbol: 'open class Pty', token: 'open class Pty' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/PtyIo.kt', line: 25, symbol: 'class PtyIo', token: 'class PtyIo' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/PtyChildProcess.kt', line: 10, symbol: 'class PtyChildProcess', token: 'class PtyChildProcess' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/TrackedCommandProtocol.kt', line: 4, symbol: 'object TrackedCommandProtocol', token: 'object TrackedCommandProtocol' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/provider/type/LocalTerminalProvider.kt', line: 34, symbol: 'class LocalTerminalProvider', token: 'class LocalTerminalProvider' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/provider/type/TerminalProvider.kt', line: 35, symbol: 'interface TerminalProvider', token: 'interface TerminalProvider' },
    { file: 'terminal/src/main/jni/pty.c', line: 98, symbol: 'forkpty', token: 'forkpty(&master_fd' },
  ],
  'terminal-ui': [
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/features/toolbox/screens/ToolboxScreen.kt', line: 206, symbol: 'fun TerminalToolScreen', token: 'fun TerminalToolScreen' },
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/main/screens/OperitScreens.kt', line: 1270, symbol: 'TerminalToolScreen', token: 'TerminalToolScreen(navController = navController)' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/main/TerminalScreen.kt', line: 55, symbol: 'fun TerminalScreen', token: 'fun TerminalScreen(' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/ui/TerminalHome.kt', line: 308, symbol: 'CanvasTerminalScreen', token: 'CanvasTerminalScreen(' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/view/canvas/CanvasTerminalCompose.kt', line: 14, symbol: 'fun CanvasTerminalScreen', token: 'fun CanvasTerminalScreen(' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/view/domain/ansi/AnsiTerminalEmulator.kt', line: 51, symbol: 'class AnsiTerminalEmulator', token: 'class AnsiTerminalEmulator(' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/view/domain/ansi/AnsiScanner.kt', line: 7, symbol: 'class AnsiScanner', token: 'class AnsiScanner(' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/TerminalEnv.kt', line: 81, symbol: 'fun rememberTerminalEnv', token: 'fun rememberTerminalEnv(' },
  ],
  'dependency-mirror': [
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/ui/SetupScreen.kt', line: 62, symbol: 'fun SetupScreen', token: 'fun SetupScreen(' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/ui/SetupScreen.kt', line: 86, symbol: 'PackageItem pnpm', token: 'PackageItem("pnpm"' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/ui/SetupScreen.kt', line: 437, symbol: 'npm install -g pnpm', token: 'npm install -g pnpm' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/ui/SetupScreen.kt', line: 646, symbol: 'pnpm probe', token: 'FOUND_PNPM' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/utils/SourceManager.kt', line: 17, symbol: 'tuna_apt', token: 'tuna_apt' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/TerminalManager.kt', line: 121, symbol: 'UBUNTU_FILENAME', token: 'UBUNTU_FILENAME' },
    { file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/TerminalManager.kt', line: 1097, symbol: 'configure_sources apt', token: 'noble main restricted universe multiverse' },
  ],
  shizuku: [
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/system/ShizukuAuthorizer.kt', line: 15, symbol: 'class ShizukuAuthorizer', token: 'class ShizukuAuthorizer' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/system/ShizukuAuthorizer.kt', line: 158, symbol: 'getOrResolveShizukuConnection', token: 'internal fun getOrResolveShizukuConnection' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/DebuggerShellExecutor.kt', line: 29, symbol: 'class DebuggerShellExecutor', token: 'class DebuggerShellExecutor' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/DebuggerShellExecutor.kt', line: 457, symbol: 'getShizukuService', token: 'private fun getShizukuService' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/DebuggerShellExecutor.kt', line: 230, symbol: 'IShizukuService.newProcess', token: 'service.newProcess(commandParts, null, null)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/DebuggerShellExecutor.kt', line: 243, symbol: 'getInputStream PFD', token: 'getInputStream' },
  ],
  'root-standard-exec': [
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/ShellExecutor.kt', line: 7, symbol: 'interface ShellExecutor', token: 'interface ShellExecutor' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/ShellExecutorFactory.kt', line: 22, symbol: 'getExecutor', token: 'fun getExecutor(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/system/AndroidShellExecutor.kt', line: 95, symbol: 'ShellExecutorFactory.getExecutor', token: 'ShellExecutorFactory.getExecutor(ctx, actualLevel)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/StandardShellExecutor.kt', line: 54, symbol: 'Runtime.exec', token: 'Runtime.getRuntime().exec(command)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/RootShellExecutor.kt', line: 514, symbol: 'Shell.cmd enqueue', token: 'Shell.cmd(command).to(stdoutCallbackList, stderrCallbackList).enqueue()' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/system/AndroidShellExecutor.kt', line: 139, symbol: 'enum ShellIdentity', token: 'enum class ShellIdentity' },
  ],
  'foreground-service': [
    { file: 'app/src/main/java/com/ai/assistance/operit/api/chat/AIForegroundService.kt', line: 101, symbol: 'class AIForegroundService', token: 'class AIForegroundService' },
    { file: 'app/src/main/java/com/ai/assistance/operit/api/chat/AIForegroundService.kt', line: 936, symbol: 'startForeground types', token: 'ForegroundServiceCompat.startForeground(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/api/chat/AIForegroundService.kt', line: 906, symbol: 'stopSelfIfIdle', token: 'private fun stopSelfIfIdle' },
    { file: 'app/src/main/java/com/ai/assistance/operit/api/chat/AIForegroundService.kt', line: 449, symbol: 'refreshBackgroundKeepAlive', token: 'fun refreshBackgroundKeepAlive' },
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/features/settings/screens/GlobalDisplaySettingsScreen.kt', line: 578, symbol: 'refreshBackgroundKeepAlive caller', token: 'AIForegroundService.refreshBackgroundKeepAlive(context)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/api/chat/AIForegroundService.kt', line: 1490, symbol: 'transparent overlay params', token: 'WindowManager.LayoutParams(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/api/chat/AIForegroundService.kt', line: 1503, symbol: 'alpha 0f', token: 'alpha = 0f' },
  ],
  'floating-window': [
    { file: 'app/src/main/java/com/ai/assistance/operit/services/FloatingChatService.kt', line: 60, symbol: 'class FloatingChatService', token: 'class FloatingChatService' },
    { file: 'app/src/main/java/com/ai/assistance/operit/services/FloatingChatService.kt', line: 291, symbol: 'FloatingWindowManager', token: 'FloatingWindowManager(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/services/floating/FloatingWindowManager.kt', line: 97, symbol: 'class FloatingWindowManager', token: 'class FloatingWindowManager(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/services/floating/FloatingWindowManager.kt', line: 187, symbol: 'windowManager.addView', token: 'windowManager.addView(composeView, params)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/floating/FloatingChatWindow.kt', line: 70, symbol: 'fun FloatingChatWindow', token: 'fun FloatingChatWindow(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/features/chat/viewmodel/FloatingWindowDelegate.kt', line: 130, symbol: 'start FloatingChatService', token: 'Intent(context, FloatingChatService::class.java)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/services/FloatingChatService.kt', line: 315, symbol: 'acquireWakeLock', token: 'private fun acquireWakeLock()' },
  ],
  quickjs: [
    { file: 'quickjs/src/main/java/com/ai/assistance/operit/core/tools/javascript/OperitQuickJsEngine.kt', line: 16, symbol: 'class OperitQuickJsEngine', token: 'class OperitQuickJsEngine' },
    { file: 'quickjs/src/main/java/com/ai/assistance/operit/core/tools/javascript/OperitQuickJsEngine.kt', line: 56, symbol: 'runtime.eval', token: 'runtime.eval(script, fileName)' },
    { file: 'quickjs/src/main/java/com/ai/assistance/operit/core/tools/javascript/QuickJsNativeRuntime.kt', line: 66, symbol: 'QuickJsNativeRuntime.create', token: 'fun create(hostBridge: HostBridge)' },
    { file: 'quickjs/src/main/cpp/quickjs_jni.cpp', line: 865, symbol: 'nativeEvaluate', token: 'Java_com_ai_assistance_operit_core_tools_javascript_QuickJsNativeBridge_nativeEvaluate' },
    { file: 'quickjs/src/main/cpp/quickjs_jni.cpp', line: 339, symbol: 'JS_Eval', token: 'JSValue result = JS_Eval(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsEngine.kt', line: 198, symbol: 'JsEngine uses QuickJS', token: 'OperitQuickJsEngine().also {' },
  ],
  toolpkg: [
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/packTool/ToolPkgParser.kt', line: 401, symbol: 'ToolPkgArchiveParser', token: 'internal object ToolPkgArchiveParser' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/packTool/ToolPkgParser.kt', line: 402, symbol: 'parseToolPkgFromIndexedEntries', token: 'fun parseToolPkgFromIndexedEntries(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/packTool/ToolPkgLoader.kt', line: 10, symbol: 'loadToolPkgFromExternalFile', token: 'fun loadToolPkgFromExternalFile(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/packTool/ToolPkgLoader.kt', line: 29, symbol: 'loader→parser', token: 'ToolPkgArchiveParser.parseToolPkgFromIndexedEntries(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/packTool/PackageManager.kt', line: 2061, symbol: 'PackageManager install', token: 'ToolPkgLoader.loadToolPkgFromExternalFile(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/packTool/ToolPkgLoadOrder.kt', line: 30, symbol: 'ToolPkgLoadOrderResolver.resolve', token: 'fun resolve(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/packTool/ToolPkgApiVersion.kt', line: 5, symbol: 'ToolPkgApiVersion', token: 'internal data class ToolPkgApiVersion' },
  ],
  'compose-dsl': [
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/packTool/ToolPkgComposeDslParser.kt', line: 20, symbol: 'object ToolPkgComposeDslParser', token: 'object ToolPkgComposeDslParser' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/packTool/ToolPkgComposeDslParser.kt', line: 21, symbol: 'parseRenderResult', token: 'fun parseRenderResult(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/common/composedsl/ToolPkgComposeDslScreen.kt', line: 703, symbol: 'ToolPkgComposeDslToolScreen', token: 'fun ToolPkgComposeDslToolScreen(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/main/screens/OperitScreens.kt', line: 1195, symbol: 'route→screen', token: 'ToolPkgComposeDslToolScreen(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/common/composedsl/ToolPkgComposeDslScreen.kt', line: 2131, symbol: 'renderer registry lookup', token: 'composeDslGeneratedNodeRendererRegistry[normalizedType]' },
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/common/composedsl/ToolPkgComposeDslGeneratedRegistry.kt', line: 7, symbol: 'composeDslGeneratedNodeRendererRegistry', token: 'composeDslGeneratedNodeRendererRegistry' },
  ],
  mcp: [
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/mcp/McpRuntimeSession.kt', line: 38, symbol: 'interface McpRuntimeSession', token: 'interface McpRuntimeSession' },
    { file: 'app/src/main/java/com/ai/assistance/operit/data/mcp/plugins/RemoteMcpRuntimeSession.kt', line: 29, symbol: 'class RemoteMcpRuntimeSession', token: 'class RemoteMcpRuntimeSession(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/data/mcp/plugins/RemoteMcpRuntimeSession.kt', line: 65, symbol: 'SseClientTransport', token: 'SseClientTransport(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/data/mcp/plugins/RemoteMcpRuntimeSession.kt', line: 117, symbol: 'callTool', token: 'override suspend fun callTool(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/mcp/MCPToolExecutor.kt', line: 263, symbol: 'invoke', token: 'override fun invoke(tool: AITool)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/mcp/MCPToolExecutor.kt', line: 279, symbol: 'mcpManager.getOrCreateSession', token: 'mcpManager.getOrCreateSession(serverName)' },
  ],
  workflow: [
    { file: 'app/src/main/java/com/ai/assistance/operit/core/workflow/WorkflowExecutor.kt', line: 79, symbol: 'class WorkflowExecutor', token: 'class WorkflowExecutor(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/workflow/WorkflowExecutor.kt', line: 527, symbol: 'executeWorkflow', token: 'suspend fun executeWorkflow(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/workflow/WorkflowExecutor.kt', line: 1275, symbol: 'toolHandler.executeTool', token: 'toolHandler.executeTool(tool)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/AIToolHandler.kt', line: 363, symbol: 'AIToolHandler.executeTool', token: 'fun executeTool(tool: AITool)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/workflow/WorkflowScheduler.kt', line: 54, symbol: 'scheduleWorkflow', token: 'fun scheduleWorkflow(workflow: Workflow)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/workflow/WorkflowWorker.kt', line: 39, symbol: 'WorkflowWorker→repository', token: 'repository.triggerWorkflow(workflowId, triggerNodeId)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/data/repository/WorkflowRepository.kt', line: 635, symbol: 'WorkflowRepository→executor', token: 'WorkflowExecutor(context)' },
  ],
  browser: [
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/standard/StandardBrowserSessionTools.kt', line: 106, symbol: 'class StandardBrowserSessionTools', token: 'class StandardBrowserSessionTools' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/ToolRegistration.kt', line: 1241, symbol: 'tool registration', token: 'ToolGetter.getBrowserSessionTools(context).invoke(tool)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/standard/StandardBrowserSessionTools.kt', line: 251, symbol: 'invoke', token: 'override fun invoke(tool: AITool)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/websession/browser/WebSessionWebViewHost.kt', line: 26, symbol: 'attachContainer', token: 'fun attachContainer(target: FrameLayout)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/websession/browser/BrowserWebViewSupport.kt', line: 844, symbol: 'webView.loadUrl', token: 'session.webView.loadUrl(targetUrl, headers)' },
  ],
  'file-provider': [
    { file: 'app/src/main/java/com/ai/assistance/operit/provider/WorkspaceDocumentsProvider.kt', line: 20, symbol: 'class WorkspaceDocumentsProvider', token: 'class WorkspaceDocumentsProvider' },
    { file: 'app/src/main/AndroidManifest.xml', line: 168, symbol: 'provider authority', token: '.provider.WorkspaceDocumentsProvider' },
    { file: 'app/src/main/java/com/ai/assistance/operit/provider/WorkspaceDocumentsProvider.kt', line: 119, symbol: 'openDocument', token: 'override fun openDocument(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/provider/WorkspaceDocumentsProvider.kt', line: 131, symbol: 'ParcelFileDescriptor.open', token: 'ParcelFileDescriptor.open(file, accessMode)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/provider/WorkspaceDocumentsProvider.kt', line: 134, symbol: 'createDocument', token: 'override fun createDocument(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/provider/WorkspaceDocumentsProvider.kt', line: 165, symbol: 'deleteDocument', token: 'override fun deleteDocument(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/provider/OperitDataDocumentsProvider.kt', line: 14, symbol: 'class OperitDataDocumentsProvider', token: 'class OperitDataDocumentsProvider' },
    { file: 'app/src/main/java/com/ai/assistance/operit/provider/OperitDataDocumentsProvider.kt', line: 107, symbol: 'data openDocument', token: 'override fun openDocument(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/provider/MemoryDocumentsProvider.kt', line: 29, symbol: 'class MemoryDocumentsProvider', token: 'class MemoryDocumentsProvider' },
  ],
  navigation: [
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/main/OperitApp.kt', line: 115, symbol: 'AppRouteCatalog.build', token: 'AppRouteCatalog.build(context)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/main/navigation/AppRouteCatalog.kt', line: 16, symbol: 'fun build', token: 'fun build(context: Context)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/main/navigation/AppRouteCatalog.kt', line: 61, symbol: 'ScreenRouteRegistry routes', token: 'ScreenRouteRegistry.hostRouteSpecs(context)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/main/screens/ScreenRouteRegistry.kt', line: 87, symbol: 'object ScreenRouteRegistry', token: 'object ScreenRouteRegistry' },
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/main/navigation/RouteBackGuard.kt', line: 18, symbol: 'register back guard', token: 'fun register(routeInstanceId: String' },
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/main/layout/PhoneLayout.kt', line: 56, symbol: 'fun PhoneLayout', token: 'fun PhoneLayout(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/main/layout/TabletLayout.kt', line: 39, symbol: 'fun TabletLayout', token: 'fun TabletLayout(' },
  ],
  'stream-markdown': [
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/common/markdown/StreamMarkdownRenderer.kt', line: 381, symbol: 'fun StreamMarkdownRenderer', token: 'fun StreamMarkdownRenderer(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/common/displays/MarkdownTextComposable.kt', line: 37, symbol: 'caller', token: 'StreamMarkdownRenderer(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/util/streamnative/NativeMarkdownSplitter.kt', line: 41, symbol: 'object NativeMarkdownSplitter', token: 'object NativeMarkdownSplitter' },
    { file: 'app/src/main/java/com/ai/assistance/operit/util/streamnative/NativeMarkdownSplitter.kt', line: 47, symbol: 'nativeCreateBlockSession', token: 'external fun nativeCreateBlockSession' },
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/common/markdown/MarkdownPlainTextRenderer.kt', line: 116, symbol: 'splitter caller', token: 'NativeMarkdownSplitter.parseInlineToStableNodes(raw)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/util/streamnative/NativeMarkdownSplitter.kt', line: 59, symbol: 'createBlockSession', token: 'fun createBlockSession(): Session' },
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/common/markdown/RenderBatchCoordinator.kt', line: 14, symbol: 'class RenderBatchCoordinator', token: 'internal class RenderBatchCoordinator(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/util/stream/RevisableTextStream.kt', line: 54, symbol: 'interface RevisableTextStream', token: 'interface RevisableTextStream' },
  ],
  voice: [
    { file: 'app/src/main/java/com/ai/assistance/operit/api/voice/VoiceServiceFactory.kt', line: 8, symbol: 'object VoiceServiceFactory', token: 'object VoiceServiceFactory' },
    { file: 'app/src/main/java/com/ai/assistance/operit/api/speech/SpeechServiceFactory.kt', line: 11, symbol: 'object SpeechServiceFactory', token: 'object SpeechServiceFactory' },
    { file: 'app/src/main/java/com/ai/assistance/operit/api/speech/SpeechServiceFactory.kt', line: 181, symbol: 'getInstance', token: 'fun getInstance(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/ui/floating/voice/SpeechInteractionManager.kt', line: 58, symbol: 'speech caller', token: 'SpeechServiceFactory.getInstance(context)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/api/speech/SpeechServiceFactory.kt', line: 134, symbol: 'SherpaSpeechProvider', token: 'SpeechServiceType.SHERPA_NCNN -> SherpaSpeechProvider(appContext)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/api/speech/SherpaSpeechProvider.kt', line: 188, symbol: 'SherpaNcnn', token: 'SherpaNcnn(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/api/speech/OnnxSileroVad.kt', line: 15, symbol: 'class OnnxSileroVad', token: 'class OnnxSileroVad(' },
  ],
  'doc-ocr': [
    { file: 'app/src/main/java/com/ai/assistance/operit/util/OCRUtils.kt', line: 28, symbol: 'object OCRUtils', token: 'object OCRUtils' },
    { file: 'app/src/main/java/com/ai/assistance/operit/util/OCRUtils.kt', line: 242, symbol: 'recognizeText', token: 'suspend fun recognizeText(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/services/core/AttachmentDelegate.kt', line: 642, symbol: 'attachment OCR caller', token: 'OCRUtils.recognizeText(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/util/OCRUtils.kt', line: 54, symbol: 'getRecognizer', token: 'private fun getRecognizer(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/util/OCRUtils.kt', line: 223, symbol: 'recognizer.process', token: '.process(image)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/util/DocumentConversionUtil.kt', line: 35, symbol: 'object DocumentConversionUtil', token: 'object DocumentConversionUtil' },
  ],
  'local-inference': [
    { file: 'llm/llama/src/main/java/com/ai/assistance/llama/LlamaNative.kt', line: 3, symbol: 'object LlamaNative', token: 'object LlamaNative' },
    { file: 'llm/llama/src/main/cpp/llama_jni_stub.cpp', line: 206, symbol: 'nativeCreateSession JNI', token: 'Java_com_ai_assistance_llama_LlamaNative_nativeCreateSession' },
    { file: 'llm/llama/src/main/java/com/ai/assistance/llama/LlamaSession.kt', line: 43, symbol: 'LlamaSession factory', token: 'return LlamaSession(ptr)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/api/chat/llmprovider/LlamaProvider.kt', line: 118, symbol: 'LlamaSession.isAvailable', token: 'if (!LlamaSession.isAvailable())' },
    { file: 'llm/mnn/src/main/java/com/ai/assistance/mnn/MNNLlmSession.kt', line: 11, symbol: 'class MNNLlmSession', token: 'class MNNLlmSession' },
    { file: 'app/src/main/java/com/ai/assistance/operit/api/chat/llmprovider/MNNProvider.kt', line: 192, symbol: 'MNNLlmSession.create', token: 'llmSession = MNNLlmSession.create(' },
  ],
  shower: [
    { file: 'showerclient/src/main/java/com/ai/assistance/showerclient/ShowerController.kt', line: 22, symbol: 'class ShowerController', token: 'class ShowerController' },
    { file: 'showerclient/src/main/java/com/ai/assistance/showerclient/ShowerController.kt', line: 34, symbol: 'getBinder', token: 'private suspend fun getBinder(' },
    { file: 'showerclient/src/main/java/com/ai/assistance/showerclient/ShowerServerManager.kt', line: 32, symbol: 'ensureServerStarted', token: 'suspend fun ensureServerStarted(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/agent/PhoneAgent.kt', line: 196, symbol: 'PhoneAgent starts server', token: 'ShowerServerManager.ensureServerStarted(context)' },
    { file: 'showerclient/src/main/java/com/ai/assistance/showerclient/ShowerController.kt', line: 279, symbol: 'service.ensureDisplay', token: 'service.ensureDisplay(targetWidth, targetHeight, dpi, bitrate)' },
    { file: 'app/src/main/java/com/ai/assistance/operit/core/tools/agent/PhoneAgent.kt', line: 217, symbol: 'PhoneAgent ensureDisplay', token: 'ShowerController.ensureDisplay(agentId, context' },
  ],
  integrations: [
    { file: 'app/src/main/java/com/ai/assistance/operit/integrations/intent/ExternalChatReceiver.kt', line: 14, symbol: 'class ExternalChatReceiver', token: 'class ExternalChatReceiver' },
    { file: 'app/src/main/java/com/ai/assistance/operit/integrations/intent/ExternalChatReceiver.kt', line: 45, symbol: 'onReceive', token: 'override fun onReceive(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/integrations/intent/ExternalChatReceiver.kt', line: 54, symbol: 'ExternalChatRequestExecutor', token: 'ExternalChatRequestExecutor(context.applicationContext).execute(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/integrations/http/ExternalChatHttpServer.kt', line: 43, symbol: 'class ExternalChatHttpServer', token: 'class ExternalChatHttpServer(' },
    { file: 'app/src/main/java/com/ai/assistance/operit/data/repository/WorkflowRepository.kt', line: 840, symbol: 'Tasker event trigger', token: 'suspend fun triggerWorkflowsByTaskerEvent' },
    { file: 'app/src/main/java/com/ai/assistance/operit/integrations/a2a/A2aTaskManager.kt', line: 64, symbol: 'class A2aTaskManager', token: 'class A2aTaskManager(' },
  ],
};

const rejected = [
  ['avatar', '虚拟形象：DragonBones/FBX/MMD/GLTF/MP4/WebP 渲染与控制器', 'app/src/main/java/com/ai/assistance/operit/core/avatar/', 'avator/dragonbones', '不采用', '产品无角色形象与 3D 展示需求；依赖多个原生引擎与素材许可，包体与运行成本无对应价值'],
  ['annual-report', '年度报告与统计图', 'app/src/main/java/com/ai/assistance/operit/data/annualreport/', 'annual-report', '不采用', 'Operit 自有运营内容；dstu 学习洞察与周报由 S7.33 基于桌面指标实现'],
  ['web-chat', 'React 网页聊天前端', 'web-chat/', null, '不采用', '构建排除清单已排除旧 React 页面；Android 为原生 Compose，桌面继续使用 dstu-node'],
  ['subpack', 'APK/EXE 反编译与图标替换', 'app/src/main/java/com/ai/assistance/operit/core/subpack/', null, '不采用', '属 Operit 打包工具链，不在 dstu 产品范围，且涉及二次分发风险'],
  ['updates', '自更新下载与补丁安装', 'app/src/main/java/com/ai/assistance/operit/data/updates/', null, '不采用', '更新与签名由 S0.15 与 S9 发布合同统一管理，不复用 Operit 更新通道'],
  ['notification-listener', '系统通知读取服务', 'app/src/main/java/com/ai/assistance/operit/services/notification/', null, '不采用', '通知监听属高敏感权限；dstu 仅展示后台/电池设置，不读取第三方通知'],
  ['desktop-widget', 'ToolPkg 桌面小组件宿主', 'app/src/main/java/com/ai/assistance/operit/widget/', null, '不采用', 'dstu 首页由 S7.34 原生 Compose 承载，不引入 AppWidget 双宿主'],
  ['voice-widget', '语音助手系统小组件', 'app/src/main/java/com/ai/assistance/operit/widget/VoiceAssistantGlanceWidget.kt', null, '不采用', '语音交互在应用内 S4.19/S4.20 交付，不建系统小组件'],
  ['demo', '演示与诊断面板', 'app/src/main/java/com/ai/assistance/operit/ui/features/demo/', null, '不采用', 'Operit 内部演示面；dstu 诊断由 S2.28 统一实现'],
  ['announcement', '远程公告', 'app/src/main/java/com/ai/assistance/operit/data/announcement/', null, '不采用', 'Operit 运营公告通道；dstu 不接入外部公告源'],
  ['codex-auth', 'Codex OAuth 与用量客户端', 'app/src/main/java/com/ai/assistance/operit/data/api/CodexAuthManager.kt', null, '不采用', 'provider 认证由 S3.02–S3.08 按桌面基线实现，不复用 Operit 账号体系'],
  ['character-card', '角色卡与角色组', 'app/src/main/java/com/ai/assistance/operit/data/preferences/CharacterCardManager.kt', null, '不采用', '角色卡是 Operit 对话人格模型；dstu 使用 Agent 技能与系统提示，数据模型不同'],
  ['backup-room', 'Room 数据库备份/恢复', 'app/src/main/java/com/ai/assistance/operit/data/backup/', null, '不采用', 'dstu 备份恢复由 S2.09–S2.15 的 DataSpace/DSBK 合同统一，避免双备份体系'],
  ['token-stats', 'Token 用量与计费统计', 'app/src/main/java/com/ai/assistance/operit/data/stats/', null, '不采用', '用量与预算由 S3.11 依桌面 llm_usage 库实现，避免两套计费口径'],
  ['theme', 'Operit 主题与液态玻璃', 'app/src/main/java/com/ai/assistance/operit/ui/theme/', null, '不采用', '视觉统一由 DstuTheme（S5.01）与 S0.11/S0.12 快照决定，Operit 调色不进入产品'],
  ['permission-ui', 'Operit 工具权限弹窗与权限引导', 'app/src/main/java/com/ai/assistance/operit/ui/permissions/', null, '不采用', '审批入口由 S2.23 CapabilityBroker 与 S5 组件实现；交互语义参考，代码不移植'],
  ['plugin-hooks', 'Operit 插件注册与聊天钩子体系', 'app/src/main/java/com/ai/assistance/operit/plugins/', null, '不采用', 'dstu 使用 UI contribution + ToolPkg 模型（S6.34/S6.44），与 Operit 钩子体系不兼容'],
  ['chat-service', 'Operit 聊天服务内核与消息委托', 'app/src/main/java/com/ai/assistance/operit/services/ChatServiceCore.kt', null, '不采用', '聊天与流管线由 S3.01–S3.11 按桌面基线实现；仅 provider 协议细节作参考'],
  ['room-model', 'Room 实体与 DAO', 'app/src/main/java/com/ai/assistance/operit/data/model/', null, '不采用', 'dstu 数据层为原生 SQLite + S0.08 迁移基线，Room 实体不可复用'],
  ['preferences', 'Operit SharedPreferences 偏好中心', 'app/src/main/java/com/ai/assistance/operit/data/preferences/', null, '不采用', '设置项分类与持久位置由 S0.06/S0.23 与 S7.35 定义，避免平行设置源'],
  ['ripgrep', '原生 ripgrep 检索', 'app/src/main/java/com/ai/assistance/operit/util/ripgrep/', null, '不采用', 'dstu 检索走 S4.24 FTS 与 S4.25 ANN，不引入额外原生二进制'],
  ['vector-index', 'Operit 向量索引', 'app/src/main/java/com/ai/assistance/operit/util/vector/', null, '不采用', 'ANN 由 S4.25 按桌面 profile 实现，索引格式不同'],
  ['assistant-service', '系统语音助手 Activity/Service', 'app/src/main/java/com/ai/assistance/operit/services/assistant/', null, '不采用', '系统级语音助手接入不在产品范围；应用内语音由 S4.19 交付'],
];

const adapters = {
  'terminal-kernel': ['AppGraph', 'TaskSupervisor', 'CapabilityBroker', 'ResourceStore'],
  'terminal-ui': ['AppGraph', 'DstuTheme'],
  'dependency-mirror': ['AppGraph', 'TaskSupervisor', 'CapabilityBroker', 'ResourceStore', 'DstuTheme'],
  shizuku: ['AppGraph', 'TaskSupervisor', 'CapabilityBroker'],
  'root-standard-exec': ['AppGraph', 'TaskSupervisor', 'CapabilityBroker'],
  'foreground-service': ['AppGraph', 'TaskSupervisor'],
  'floating-window': ['AppGraph', 'TaskSupervisor', 'DstuTheme'],
  quickjs: ['AppGraph', 'TaskSupervisor', 'CapabilityBroker'],
  toolpkg: ['AppGraph', 'CapabilityBroker', 'ResourceStore'],
  'compose-dsl': ['AppGraph', 'CapabilityBroker', 'DstuTheme'],
  mcp: ['AppGraph', 'TaskSupervisor', 'CapabilityBroker', 'ResourceStore'],
  workflow: ['AppGraph', 'TaskSupervisor', 'CapabilityBroker'],
  browser: ['AppGraph', 'TaskSupervisor', 'CapabilityBroker', 'ResourceStore'],
  'file-provider': ['AppGraph', 'ResourceStore'],
  navigation: ['AppGraph', 'DstuTheme'],
  'stream-markdown': ['AppGraph', 'DstuTheme'],
  voice: ['AppGraph', 'TaskSupervisor', 'CapabilityBroker'],
  'doc-ocr': ['AppGraph', 'TaskSupervisor', 'ResourceStore'],
  'local-inference': ['AppGraph', 'TaskSupervisor', 'ResourceStore'],
  shower: ['AppGraph', 'TaskSupervisor', 'CapabilityBroker'],
  integrations: ['AppGraph', 'TaskSupervisor', 'CapabilityBroker'],
};

const persistence = {
  'terminal-kernel': 'local: files/terminal/environments/<environmentId> 与 runtime.db tasks/events；会话历史按设备归属',
  'terminal-ui': 'local: 字体/字号/键盘偏好进入本机设置，不进入同步行',
  'dependency-mirror': 'local: 源与代理按设备保存；安装回执进 runtime.db，包版本进入环境 manifest',
  shizuku: 'local: 授权状态与 UID 只存本机；命令审计进 runtime.db audit',
  'root-standard-exec': 'local: 执行身份选择按设备保存；审计进 runtime.db audit',
  'foreground-service': 'local: 常驻开关与职责引用只在本机；任务账本 runtime.db',
  'floating-window': 'local: 浮窗位置/尺寸/皮肤本机保存；会话内容走聊天库',
  quickjs: 'local: 引擎句柄与内存预算不持久；脚本产物经 ResourceStore 提交',
  toolpkg: 'portable: 包清单与 API 版本随 S0.24 插件 data 合同；安装与信任本机',
  'compose-dsl': 'local: UI 贡献树由包声明；渲染状态不持久，导航栈进工作台快照',
  mcp: 'local: 服务器配置与凭据按 secret/local 分类；工具结果经会话持久',
  workflow: 'portable: 工作流图与节点定义进同步行；运行记录进 runtime.db（本机）',
  browser: 'local: 会话/登录态/历史按设备保存；下载产物经 ResourceStore',
  'file-provider': 'local: documentId 与授权按设备；内容归 ResourceStore 内容寻址',
  navigation: 'local: 导航折叠与最近视图按设备，不进入同步',
  'stream-markdown': 'local: 渲染缓存不持久；消息内容归聊天库',
  voice: 'local: TTS/STT provider 配置与密钥按 local/secret；播放进度按 S0.22 事件',
  'doc-ocr': 'local: OCR 结果与页面缓存为派生数据；原文归资源库',
  'local-inference': 'local: 模型文件与下载状态按设备；对话内容归聊天库',
  shower: 'local: 虚拟显示与后端进程不持久；设备动作审计进 runtime.db',
  integrations: 'local: HTTP/A2A/Tasker 接入开关与令牌本机；触发结果进任务账本',
};

const executionIdentity = {
  'terminal-kernel': 'APP/LINUX（应用 UID 承载的 PRoot Ubuntu）；创建会话时固定 target、实际 UID、cwd 与环境',
  'terminal-ui': '不执行命令；渲染对应会话的 target 与身份标签',
  'dependency-mirror': 'LINUX（PRoot guest root）；APT 与语言包不在 SHIZUKU/ROOT 执行',
  shizuku: 'SHIZUKU（Shizuku/Sui 系统身份；后端报告的 2000/0 按实际值记录）',
  'root-standard-exec': 'APP（StandardShellExecutor）、ROOT（libsu/RootShellExecutor）、SHIZUKU（DebuggerShellExecutor）显式路由',
  'foreground-service': '不执行命令；持有前台职责与通知，任务由 RuntimeController 归属',
  'floating-window': '继承宿主会话身份，不自行提升权限',
  quickjs: 'isolated 脚本服务；能力调用逐次经 CapabilityBroker 校验调用者身份',
  toolpkg: '继承安装时包授权；工具执行经 ToolDispatcher 按 target 路由',
  'compose-dsl': '不执行；事件回调进入同一 CapabilityBroker 入口',
  mcp: '远端为网络客户端身份；本地 stdio 经 LINUX target 启动',
  workflow: '节点按声明 target 执行；写操作需审批',
  browser: 'APP（WebView 进程内）；用户接管沿用同一会话',
  'file-provider': 'APP；外部应用经 SAF 授权，写入经 ResourceStore 审核',
  navigation: '不执行',
  'stream-markdown': '不执行',
  voice: 'APP；云端 provider 使用本机凭据',
  'doc-ocr': 'APP（ML Kit 本地识别），不联网',
  'local-inference': 'APP 进程内 JNI；模型文件只读挂载',
  shower: 'SHIZUKU shell 启动后端服务，Binder 校验后创建虚拟显示',
  integrations: 'APP 广播/HTTP 服务；触发任务按声明 target 执行',
};

const ownerRoles = {
  'terminal-kernel': 'main-channel: 终端纵切负责人（S1.20 建立，S6.02 完善）',
  'terminal-ui': 'main-channel: 终端界面负责人（S6.03，主题受 S5.06 合同约束）',
  'dependency-mirror': 'main-channel: 环境与依赖负责人（S1.21 纵切，S6.11 安装中心）',
  shizuku: 'main-channel: 系统执行身份负责人（S1.22 纵切，S6.18–S6.24）',
  'root-standard-exec': 'main-channel: 四目标 ShellRouter 负责人（S2.24）',
  'foreground-service': 'main-channel: 后台与任务负责人（S1.09、S2.20–S2.21）',
  'floating-window': 'main-channel: 浮窗宿主负责人（S6.40）',
  quickjs: 'main-channel: 脚本引擎负责人（S6.31–S6.32）',
  toolpkg: 'main-channel: 包生态负责人（S6.33、S6.35）',
  'compose-dsl': 'main-channel: UI 贡献宿主负责人（S6.34、S6.44）',
  mcp: 'main-channel: MCP 与 Agent 工具负责人（S6.28–S6.30）',
  workflow: 'main-channel: 自动化与工作流负责人（S3.25）',
  browser: 'main-channel: 浏览器负责人（S6.37–S6.38）',
  'file-provider': 'main-channel: 资源与 Provider 负责人（S2.25、S6.39）',
  navigation: 'main-channel: 导航壳负责人（S5.05）',
  'stream-markdown': 'main-channel: 流式内容负责人（S5.19）',
  voice: 'main-channel: 媒体与语音负责人（S4.19–S4.20）',
  'doc-ocr': 'main-channel: 文档与识别负责人（S4.07、S4.11）',
  'local-inference': 'main-channel: 本地模型负责人（S4.21–S4.22）',
  shower: 'main-channel: 系统增强负责人（S6.41–S6.42）',
  integrations: 'main-channel: 外部集成负责人（S6.36）',
};

const acceptance = {
  'terminal-kernel': 'stty size/resize、中文与 emoji 列宽、Ctrl+C、多会话输出隔离、退出码与命令回执；stdout/stderr 同时大量输出与进程组取消',
  'terminal-ui': 'ANSI 序列夹具逐字节一致、真彩与滚动区、选区/复制、IME 与 inset、DstuTheme 亮暗对照',
  'dependency-mirror': 'apt/pip/npm/rust 实际访问选定源；pnpm 真实安装并按 npm prefix 探测（修正 SetupScreen.kt:86 的 typescript 误映射）；rootfs 解包后重读代号与 apt 源一致；中断/低空间/重入状态机',
  shizuku: '未安装/未启动/未授权/已授权/撤权/Binder 死亡状态准确；id 与后端实际 UID 一致；stdout/stderr 灌满不阻塞',
  'root-standard-exec': '四目标实跑并核对 UID；身份切换创建新会话；取消由进程所有者执行；并发双流无死锁',
  'foreground-service': 'Activity 关闭后任务推进；通知含 taskId 与停止动作；最后职责释放 stopForeground+stopSelf；常驻开关跨启动保存',
  'floating-window': '授权/拖动/收起/关闭/返回；任务与浮窗动作独立；WakeLock 释放与崩溃计数停止',
  quickjs: '固定上游提交构建；死循环中断、内存预算、能力 RPC 越权拒绝；JNI 符号与 ABI 核验',
  toolpkg: '真实包解析/加载；缺依赖与错版本明确结果；升级保留数据、卸载移除工具',
  'compose-dsl': '真实贡献树可操作；主题/授权 URI/文件选择/导航进统一边界；未知组件明确拒绝',
  mcp: 'SSE 与 Streamable HTTP 真实 initialize/list/call；认证/取消/关闭；本地 stdio 的 stdout 协议与 stderr 日志分离',
  workflow: '图编辑/环检测/拓扑执行；触发/停用/取消；所有节点工具进入统一审批路径',
  browser: '登录态保持、导航/关闭/接管；DOM ref 过期与跨 origin 策略；下载产物经资源库读回 hash',
  'file-provider': '系统文件管理器读写经 ResourceStore 产生一次变更；撤权与越界拒绝；私有治理文件不可见',
  navigation: '手机/平板重排、返回守卫、旋转与重启恢复；路由与 S0.02 清单一致',
  'stream-markdown': '稳定块增量渲染；公式/代码/表格分段；中断与完成态一致；亮暗主题',
  voice: 'TTS 队列不重叠、取消静音与焦点恢复；STT 分段与 VAD 时间轴；provider 切换释放旧实例',
  'doc-ocr': '固定扫描集文字质量与框位置；损坏/加密文件分类；页面栅格化与资源释放',
  'local-inference': '固定模型真实生成、取消释放、错误来源可查；模型 hash/ABI/内存预算',
  shower: 'shell 实际身份启动后端；截图/滑动/按键生效；后端死亡显式结束与重建',
  integrations: '外部 HTTP/A2A/Tasker 触发进同一任务账本；未授权入口拒绝；触发幂等',
};

const risks = {
  'terminal-kernel': ['TerminalManager 单例持有 Context 与 filesDir，需改为 AppGraph 注入的独立文件根', 'PTY 单流语义与四目标分流元数据需在 ProcessSupervisor 对齐'],
  'terminal-ui': ['Canvas 渲染直接消费 Operit 调色与字体，需替换为 DstuTheme token', 'TerminalScreen 自带 NavHost，需并入 dstu 导航与返回守卫'],
  'dependency-mirror': ['SetupScreen.kt:86 pnpm 项 recipe 为 "typescript"，与 :437 实际 npm install -g pnpm 及 :646 npm prefix 探测不一致，必须修正', 'rootfs 实际代号已核实为 noble/24.04（lsb-release、内置 apt 源、安装脚本三者一致）；S6.09 仍须在解包后重读 lsb-release 而非沿用文件名', 'dpkg 状态与版本探测需机器可读输出'],
  shizuku: ['DebuggerShellExecutor 先串行读 stdout 再读 stderr（:243 起），需并发排空避免管道写满死锁', '反射 PFD 与 newProcess 依赖固定 IShizukuService API 版本，需登记版本窗口'],
  'root-standard-exec': ['ShellExecutorFactory.getHighestAvailableExecutor 按 ROOT→STANDARD 降级，dstu 必须改为显式 target 选择', 'StandardShellExecutor 用 Runtime.exec 单流，需并发读与进程组取消'],
  'foreground-service': ['AIForegroundService 混合语音监听/外部 HTTP/常驻职责，需拆为职责集合与通知合同', '透明 1×1 overlay（:1490/:1503）仅列内测对照，不作为正式保活手段'],
  'floating-window': ['浮窗与聊天服务强耦合 ChatServiceCore，需改为任务/会话绑定', '崩溃计数与 WakeLock 需接入统一任务账本与电耗记录'],
  quickjs: ['QuickJS 由 CMake 在构建期从网络克隆（operit_git_source.cmake），需改为 S0.01 已锁定提交的离线来源', 'ABI 仅 arm64-v8a 且 minSdk 26，需按 S0.15 统一'],
  toolpkg: ['安装未绑定签名/权限账本，需在 S6.35 补齐', '解析器依赖 JsEngine 执行注册脚本，需在 isolated 服务内运行'],
  'compose-dsl': ['生成注册表由 Python 工具产出，需纳入固定构建步骤', '文件选择/相机/导航直接使用 Activity Result，需改为宿主端口'],
  mcp: ['远端会话使用 Ktor 与官方 Kotlin SDK，需核版本与取消语义', '本地 stdio 由 MCPStarter 经终端启动，需与 S6.30 的环境/日志合同合并'],
  workflow: ['WorkflowExecutor 直接 new AIToolHandler，需改为注入 ToolDispatcher', 'WorkflowScheduler 受 WorkManager 15 分钟最小间隔约束，需与 S2.22 对齐'],
  browser: ['WebView 宿主与工具执行器同进程，需确认渲染进程隔离与用户接管', '下载与上传需接 ResourceStore，不得直接写应用私有目录'],
  'file-provider': ['Provider authority 使用 ${applicationId} 前缀，需在 S0.15 固定 applicationId 后生成', 'openDocument 直接返回 PFD，写入路径需经 ResourceStore 审核'],
  navigation: ['AppRouteCatalog 从 ScreenRouteRegistry 生成，Operit 路由需映射到 dstu 17 视图而非照搬', 'Phone/Tablet 以固定断点实现，需接入窗口尺寸类'],
  'stream-markdown': ['原生 splitter 依赖 streamnative JNI 与 CMake 构建', '渲染器使用 Operit 字阶与颜色，需换 DstuTheme'],
  voice: ['SpeechServiceFactory 对本地 provider 做单例与引用计数，需与任务取消合并', '云端 provider 密钥需进入 secret 分类'],
  'doc-ocr': ['ML Kit 模型随 Play Services 下发，需登记离线可用性', 'OCR 结果属派生数据，需与 S4.13 队列合并'],
  'local-inference': ['llama.cpp 构建期从网络克隆固定提交，需改为离线来源', 'MNN 与 llama 双引擎需按 S4.21 明确默认与预算'],
  shower: ['后端 APK/JAR 分发与 Binder 校验需在 S0.14 登记资源与许可', '虚拟显示依赖 shell 身份，撤权后必须显式结束'],
  integrations: ['外部 HTTP 服务默认关闭，需与常驻职责和端口校验合并', 'A2A/Tasker 入口需统一审批与幂等'],
};

const relatedDomains = {
  'terminal-kernel': ['vfs', 'sync-support'],
  'terminal-ui': ['notes', 'chat'],
  'dependency-mirror': ['plugin-workshop', 'mcp'],
  shizuku: ['mobile-compat', 'integration'],
  'root-standard-exec': ['mobile-compat', 'integration'],
  'foreground-service': ['agent-orchestration', 'chat-pipeline'],
  'floating-window': ['chat', 'agent-orchestration'],
  quickjs: ['plugin-workshop', 'agent-skills'],
  toolpkg: ['plugin-workshop', 'integration'],
  'compose-dsl': ['plugin-workshop', 'notes'],
  mcp: ['mcp', 'agent-orchestration'],
  workflow: ['agent-orchestration', 'chat-pipeline'],
  browser: ['browser', 'memory'],
  'file-provider': ['vfs', 'vfs-handlers'],
  navigation: ['notes', 'chat'],
  'stream-markdown': ['chat', 'notes'],
  voice: ['media', 'translation'],
  'doc-ocr': ['media', 'vfs-index'],
  'local-inference': ['llm', 'memory'],
  shower: ['mobile-compat', 'integration'],
  integrations: ['integration', 'ilink'],
};

const relatedBrandPages = {
  'terminal-kernel': ['terminal'],
  'terminal-ui': ['terminal'],
  'dependency-mirror': ['terminal-setup', 'terminal-auto-config', 'install-center'],
  shizuku: ['shizuku-permissions', 'shizuku-install'],
  'root-standard-exec': ['shizuku-permissions', 'app-permissions', 'tool-permissions'],
  'foreground-service': ['background-keepalive'],
  'floating-window': ['floating-window'],
  quickjs: ['toolpkg-host'],
  toolpkg: ['toolpkg-host', 'install-center'],
  'compose-dsl': ['toolpkg-host'],
  mcp: ['mcp-config'],
  workflow: [],
  browser: [],
  'file-provider': [],
  navigation: [],
  'stream-markdown': [],
  voice: [],
  'doc-ocr': [],
  'local-inference': [],
  shower: ['permission-guide'],
  integrations: ['mcp-config'],
};

// Frozen desktop capabilities are a separate adoption source from Operit. S0.04/S0.05
// declare platform-independent contracts only; Kotlin services and the APK are not built
// yet. The entries here name future native adapters. The upstream Android/Capacitor
// mobile profile is not a root here and is never scheduled for porting.
const desktopSeams = [
  {
    id: 'desktop-plugin-services',
    owner: 'integrations',
    ownershipBasis: 'community plugin service facade and external integration boundary; plugin-services is not a terminal/dependency-install dependency',
    relatedUnits: ['S6.34', 'S6.35', 'S6.36', 'S3.17', 'S5.07', 'S7.08', 'S7.18', 'S4.29', 'S2.01', 'S2.13'],
    services: [
      ['pluginSecrets', 'plugin-services/PluginSecretsService', 'public', 'S6.35', 'integrations', 'S2.13'],
      ['pluginNotify', 'plugin-services/PluginNotifyService', 'public', 'S6.35', 'integrations'],
      ['pluginSchedule', 'plugin-services/PluginScheduleService', 'public', 'S6.35', 'integrations'],
      ['studyData', 'plugin-services/StudyDataService', 'public', 'S6.35', 'integrations'],
      ['pluginServices', 'plugin-services:provide:host/plugins/plugin-services/index.ts:47', 'registration', 'S6.35', 'integrations'],
      ['resolver', 'plugin-services/Services', 'internal', 'S6.35', 'integrations', 'S5.07/S7.08/S7.18/S4.29/S2.01'],
    ],
    files: [
      'host/plugins/plugin-services/index.ts',
      'host/plugins/plugin-services/api.ts',
      'host/plugins/plugin-services/src/secrets.ts',
      'host/plugins/plugin-services/src/notify.ts',
      'host/plugins/plugin-services/src/schedule.ts',
      'host/plugins/plugin-services/src/study-data-types.ts',
      'host/plugins/plugin-services/src/study-data.ts',
    ],
    android: 'future native adapters: S6.35 builds AppGraph/CapabilityBroker-backed owner-isolated secrets, notifications, scheduling and study-data surfaces; S6.34 consumes views/theme variants, S6.36 refreshes the workshop after install/lifecycle events; the Kotlin seam is not built yet and the upstream mobile.ts or Capacitor chain is not copied',
  },
  {
    id: 'desktop-workbench-plugin-view',
    owner: 'compose-dsl',
    ownershipBasis: 'compose-dsl delivers S6.34; S6.43 and S7.37 remain later plan responsibilities rather than adopted units',
    relatedUnits: ['S6.34', 'S6.43', 'S7.37'],
    services: [],
    files: ['src/features/workbench/apps/plugin-views/register.tsx', 'src/features/workbench/apps/plugin-views/PluginViewAppWindow.tsx'],
    android: 'future native adapters: S6.34 owns the dynamic contribution tree and slot revocation, S6.43 the formal workbench window/focus/beside host, S7.37 per-package install/run evidence; typeId remains plugin-view.<viewKey>, single instance, owner follows slot entry revocation; the upstream mobile chain is not adopted',
  },
];

const dependencies = {
  'terminal-kernel': ['S0.14 PTY/proot/bash/busybox/loader 资源登记', 'S1.01 Gradle 与 CMake/JNI 构建', 'S1.02 AppGraph'],
  'terminal-ui': ['S5.06 Operit 宿主主题适配层', 'S1.20 终端内核', 'S1.11 DstuTheme'],
  'dependency-mirror': ['S1.21 PRoot 纵切', 'S6.09 rootfs 安装', 'S0.14 rootfs 资源与许可'],
  shizuku: ['S0.14 Shizuku 依赖版本', 'S1.22 授权纵切', 'S2.23 CapabilityBroker'],
  'root-standard-exec': ['S2.23 CapabilityBroker', 'S1.20/S1.21/S1.22 三目标纵切', 'S0.14 libsu 版本'],
  'foreground-service': ['S1.08 任务账本', 'S2.17 lease 与取消', 'S2.20 FGS 合同'],
  'floating-window': ['S2.20 FGS 合同', 'S6.04 多会话', 'S3.27 聊天页面'],
  quickjs: ['S0.14 QuickJS 上游提交与构建脚本', 'S1.19 PTC 最小执行链', 'S6.32 isolated 宿主'],
  toolpkg: ['S6.32 isolated 宿主', 'S0.24 插件 data 合同', 'S6.35 安装/升级/卸载'],
  'compose-dsl': ['S6.33 ToolPkg 解析', 'S1.11 DstuTheme', 'S5.03 容器组件'],
  mcp: ['S1.07 HTTP/SSE', 'S3.17 ToolDispatcher', 'S6.30 本地 stdio'],
  workflow: ['S2.22 WorkManager 调度', 'S3.17 ToolDispatcher', 'S2.16 runtime.db'],
  browser: ['S1.15 内容与浏览器宿主', 'S3.17 ToolDispatcher', 'S2.06 内容寻址资源'],
  'file-provider': ['S2.06 内容寻址', 'S2.07 SAF/PFD', 'S2.25 Provider 合同'],
  navigation: ['S0.02 视图/导航清单', 'S5.03 容器组件', 'S5.05 全导航壳'],
  'stream-markdown': ['S0.07 内容方言', 'S3.10 块增量', 'S5.19 聊天块渲染'],
  voice: ['S0.14 sherpa/ncnn 模型与许可', 'S4.17 音频管线', 'S0.22 媒体事件'],
  'doc-ocr': ['S0.14 ML Kit/模型来源', 'S4.05 PDF 渲染', 'S4.10 图像处理'],
  'local-inference': ['S0.14 llama.cpp/MNN 来源与 ABI', 'S3.02 provider 接口', 'S4.02 引擎宿主'],
  shower: ['S0.14 shower 后端与 Binder', 'S6.23 ShellUserService 增强', 'S6.22 系统工具'],
  integrations: ['S6.35 包安装', 'S2.22 调度', 'S3.17 ToolDispatcher'],
};

const value = {
  'terminal-kernel': '提供真实 PTY 与多会话后台命令，是 Agent 执行、依赖安装与本地 MCP 的基础',
  'terminal-ui': '让终端在手机上可交互并复用桌面视觉语言，承载输出、选区与 IME',
  'dependency-mirror': '把 Ubuntu 环境与语言包安装变成可审批、可重入的产品能力',
  shizuku: '在无 root 设备上获得系统身份，支撑 pm/settings/input 与设备操作',
  'root-standard-exec': '统一 APP/LINUX/SHIZUKU/ROOT 四目标路由与身份记录',
  'foreground-service': '保证长任务在 Activity 关闭后继续，并让用户可见可停',
  'floating-window': '让对话与终端在跨应用场景中保持可见',
  quickjs: '为 ToolPkg、PTC 与声明式 UI 提供受限脚本执行',
  toolpkg: '形成可安装、可版本化的工具包生态',
  'compose-dsl': '让包以声明式 UI 扩展页面而不编译进主包',
  mcp: '连接远端与本地 MCP 服务器，扩展 Agent 工具面',
  workflow: '把工具调用编排为可调度、可审计的自动化',
  browser: '提供带登录态与 DOM 操作的浏览能力',
  'file-provider': '让外部应用在授权范围内读写工作区与附件',
  navigation: '提供手机/平板自适应导航与返回守卫的成熟实现参考',
  'stream-markdown': '稳定块增量渲染，避免流式输出闪烁与重排',
  voice: '提供 TTS/STT/VAD 与本地/云端 provider 的完整实现',
  'doc-ocr': '提供本地 OCR 与文档格式转换，支撑资料导入',
  'local-inference': '提供离线 llama/MNN 推理与嵌入能力',
  shower: '以 shell 身份创建虚拟显示，支撑设备 Agent 截图与触控',
  integrations: '把外部触发（HTTP/A2A/Tasker/分享）接入统一任务与审批',
};

// 真实调用链：caller → 中间符号 → 真实执行端；每环节均校验文件行内的 token。
const terminalStages = ['executor', 'jni', 'mnn-executor', 'guard', 'a2a', 'tts', 'vad', 'converter', 'content', 'identity', 'scheduler'];
const chains = {
  'terminal-kernel': [
    ['app/src/main/java/com/ai/assistance/operit/core/tools/ToolRegistration.kt', 327, 'execute_in_terminal_session 工具注册', 'terminalTool.executeCommandInSession(tool)', 'caller'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/standard/StandardTerminalCommandExecutor.kt', 126, 'StandardTerminalCommandExecutor.executeCommandInSession', 'terminal.executeCommandFlow(sessionId, command)', 'service'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/system/Terminal.kt', 179, 'Terminal.executeCommandFlow', 'terminalManager.sendCommandToSession(sessionId, command, commandId)', 'facade'],
    ['terminal/src/main/java/com/ai/assistance/operit/terminal/TerminalManager.kt', 319, 'TerminalManager.sendCommandToSession', 'executeCommandInternal(command, session, actualCommandId, tracksExitStatus = true)', 'kernel'],
    ['terminal/src/main/java/com/ai/assistance/operit/terminal/TerminalManager.kt', 360, 'TerminalManager.handleRegularCommand', 'writeInputToKernel(session, fullInput, "command")', 'kernel'],
    ['terminal/src/main/java/com/ai/assistance/operit/terminal/PtyIo.kt', 95, 'PtyIo.writeBytes', 'Os.write(descriptor, buffer, offset + written, length - written)', 'pty-io'],
    ['terminal/src/main/java/com/ai/assistance/operit/terminal/Pty.kt', 103, 'Pty.createSubprocess', 'private external fun createSubprocess', 'jni'],
    ['terminal/src/main/jni/pty.c', 98, 'forkpty', 'forkpty(&master_fd', 'executor'],
  ],
  'terminal-ui': [
    ['app/src/main/java/com/ai/assistance/operit/ui/main/screens/OperitScreens.kt', 1270, 'Terminal 视图入口', 'TerminalToolScreen(navController = navController)', 'caller'],
    ['app/src/main/java/com/ai/assistance/operit/ui/features/toolbox/screens/ToolboxScreen.kt', 206, 'TerminalToolScreen', 'fun TerminalToolScreen', 'host'],
    ['terminal/src/main/java/com/ai/assistance/operit/terminal/main/TerminalScreen.kt', 55, 'TerminalScreen', 'fun TerminalScreen(', 'screen'],
    ['terminal/src/main/java/com/ai/assistance/operit/terminal/ui/TerminalHome.kt', 308, 'TerminalHome', 'CanvasTerminalScreen(', 'screen'],
    ['terminal/src/main/java/com/ai/assistance/operit/terminal/view/canvas/CanvasTerminalCompose.kt', 14, 'CanvasTerminalScreen', 'fun CanvasTerminalScreen(', 'render'],
    ['terminal/src/main/java/com/ai/assistance/operit/terminal/view/domain/ansi/AnsiTerminalEmulator.kt', 51, 'AnsiTerminalEmulator', 'class AnsiTerminalEmulator(', 'emulator'],
    ['terminal/src/main/java/com/ai/assistance/operit/terminal/view/domain/ansi/AnsiScanner.kt', 7, 'AnsiScanner', 'class AnsiScanner(', 'executor'],
  ],
  'dependency-mirror': [
    ['app/src/main/java/com/ai/assistance/operit/ui/main/screens/OperitScreens.kt', 1286, 'TerminalSetup 视图', 'TerminalToolScreen(navController = navController, forceShowSetup = true)', 'caller'],
    ['terminal/src/main/java/com/ai/assistance/operit/terminal/main/TerminalScreen.kt', 124, 'TerminalScreen SETUP_ROUTE', 'SetupScreen(', 'route'],
    ['terminal/src/main/java/com/ai/assistance/operit/terminal/ui/SetupScreen.kt', 62, 'SetupScreen', 'fun SetupScreen(', 'screen'],
    ['terminal/src/main/java/com/ai/assistance/operit/terminal/ui/SetupScreen.kt', 158, 'SetupScreen 建会话', 'terminalManager.createNewSession("setup-check")', 'session'],
    ['terminal/src/main/java/com/ai/assistance/operit/terminal/ui/SetupScreen.kt', 700, 'executeCommandAndGetOutput', 'terminalManager.sendCommand(command, commandId)', 'command'],
    ['terminal/src/main/java/com/ai/assistance/operit/terminal/TerminalManager.kt', 1097, 'configure_sources', 'noble main restricted universe multiverse', 'apt-source'],
    ['terminal/src/main/java/com/ai/assistance/operit/terminal/utils/SourceManager.kt', 17, 'SourceManager APT 镜像', 'tuna_apt', 'executor'],
  ],
  shizuku: [
    ['app/src/main/java/com/ai/assistance/operit/core/tools/ToolRegistration.kt', 277, 'execute_shell 工具注册', 'ToolGetter.getShellToolExecutor(context)', 'caller'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/standard/StandardShellToolExecutor.kt', 42, 'StandardShellToolExecutor.invoke', 'AndroidShellExecutor.executeShellCommand(command)', 'service'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/system/AndroidShellExecutor.kt', 95, 'AndroidShellExecutor.executeShellCommand', 'ShellExecutorFactory.getExecutor(ctx, actualLevel)', 'router'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/ShellExecutorFactory.kt', 22, 'ShellExecutorFactory.getExecutor', 'fun getExecutor(', 'factory'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/DebuggerShellExecutor.kt', 457, 'DebuggerShellExecutor.getShizukuService', 'private fun getShizukuService', 'authorizer'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/DebuggerShellExecutor.kt', 230, 'IShizukuService.newProcess', 'service.newProcess(commandParts, null, null)', 'binder'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/DebuggerShellExecutor.kt', 243, 'PFD stdout', 'getInputStream', 'executor'],
  ],
  'root-standard-exec': [
    ['app/src/main/java/com/ai/assistance/operit/core/tools/ToolRegistration.kt', 271, 'execute_shell 工具注册', 'name = "execute_shell"', 'caller'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/system/AndroidShellExecutor.kt', 95, 'AndroidShellExecutor', 'ShellExecutorFactory.getExecutor(ctx, actualLevel)', 'router'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/ShellExecutorFactory.kt', 22, 'ShellExecutorFactory', 'fun getExecutor(', 'factory'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/StandardShellExecutor.kt', 54, 'StandardShellExecutor', 'Runtime.getRuntime().exec(command)', 'app-executor'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/RootShellExecutor.kt', 514, 'RootShellExecutor', 'Shell.cmd(command).to(stdoutCallbackList, stderrCallbackList).enqueue()', 'root-executor'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/system/AndroidShellExecutor.kt', 139, 'ShellIdentity', 'enum class ShellIdentity', 'identity'],
  ],
  'foreground-service': [
    ['app/src/main/java/com/ai/assistance/operit/ui/features/settings/screens/GlobalDisplaySettingsScreen.kt', 578, '后台常驻设置', 'AIForegroundService.refreshBackgroundKeepAlive(context)', 'caller'],
    ['app/src/main/java/com/ai/assistance/operit/api/chat/AIForegroundService.kt', 449, 'refreshBackgroundKeepAlive', 'fun refreshBackgroundKeepAlive', 'service'],
    ['app/src/main/java/com/ai/assistance/operit/api/chat/AIForegroundService.kt', 936, 'onCreate startForeground', 'ForegroundServiceCompat.startForeground(', 'fgs'],
    ['app/src/main/java/com/ai/assistance/operit/api/chat/AIForegroundService.kt', 906, 'stopSelfIfIdle', 'private fun stopSelfIfIdle', 'release'],
    ['app/src/main/java/com/ai/assistance/operit/api/chat/AIForegroundService.kt', 1490, 'keep-alive overlay', 'WindowManager.LayoutParams(', 'internal-test'],
    ['app/src/main/java/com/ai/assistance/operit/api/chat/AIForegroundService.kt', 1503, 'overlay alpha', 'alpha = 0f', 'executor'],
  ],
  'floating-window': [
    ['app/src/main/java/com/ai/assistance/operit/ui/features/chat/viewmodel/FloatingWindowDelegate.kt', 130, 'FloatingWindowDelegate', 'Intent(context, FloatingChatService::class.java)', 'caller'],
    ['app/src/main/java/com/ai/assistance/operit/services/FloatingChatService.kt', 60, 'FloatingChatService', 'class FloatingChatService', 'service'],
    ['app/src/main/java/com/ai/assistance/operit/services/FloatingChatService.kt', 291, 'createWindowManager', 'FloatingWindowManager(', 'manager'],
    ['app/src/main/java/com/ai/assistance/operit/services/floating/FloatingWindowManager.kt', 187, 'FloatingWindowManager.show', 'windowManager.addView(composeView, params)', 'window'],
    ['app/src/main/java/com/ai/assistance/operit/ui/floating/FloatingChatWindow.kt', 70, 'FloatingChatWindow', 'fun FloatingChatWindow(', 'content'],
    ['app/src/main/java/com/ai/assistance/operit/services/FloatingChatService.kt', 315, 'acquireWakeLock', 'private fun acquireWakeLock()', 'executor'],
  ],
  quickjs: [
    ['app/src/main/java/com/ai/assistance/operit/core/tools/packTool/ToolPkgManager.kt', 26, 'ToolPkgManager 执行引擎', 'private val createExecutionEngine: () -> JsEngine = { JsEngine(context) }', 'caller'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsEngine.kt', 198, 'JsEngine 初始化', 'OperitQuickJsEngine().also {', 'host'],
    ['quickjs/src/main/java/com/ai/assistance/operit/core/tools/javascript/OperitQuickJsEngine.kt', 56, 'OperitQuickJsEngine.evaluate', 'runtime.eval(script, fileName)', 'engine'],
    ['quickjs/src/main/java/com/ai/assistance/operit/core/tools/javascript/QuickJsNativeRuntime.kt', 66, 'QuickJsNativeRuntime.create', 'fun create(hostBridge: HostBridge)', 'runtime'],
    ['quickjs/src/main/cpp/quickjs_jni.cpp', 865, 'nativeEvaluate', 'Java_com_ai_assistance_operit_core_tools_javascript_QuickJsNativeBridge_nativeEvaluate', 'jni'],
    ['quickjs/src/main/cpp/quickjs_jni.cpp', 339, 'JS_Eval', 'JSValue result = JS_Eval(', 'executor'],
  ],
  toolpkg: [
    ['app/src/main/java/com/ai/assistance/operit/core/tools/packTool/PackageManager.kt', 2061, 'PackageManager 安装', 'ToolPkgLoader.loadToolPkgFromExternalFile(', 'caller'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/packTool/ToolPkgLoader.kt', 29, 'ToolPkgLoader', 'ToolPkgArchiveParser.parseToolPkgFromIndexedEntries(', 'loader'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/packTool/ToolPkgParser.kt', 402, 'ToolPkgArchiveParser', 'fun parseToolPkgFromIndexedEntries(', 'parser'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/packTool/ToolPkgManager.kt', 102, 'ToolPkgManager 加载顺序', 'ToolPkgLoadOrderResolver.resolve(', 'resolver'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/packTool/ToolPkgLoadOrder.kt', 30, 'ToolPkgLoadOrderResolver', 'fun resolve(', 'executor'],
  ],
  'compose-dsl': [
    ['app/src/main/java/com/ai/assistance/operit/ui/main/screens/OperitScreens.kt', 1195, 'ToolPkgComposeDsl 视图', 'ToolPkgComposeDslToolScreen(', 'caller'],
    ['app/src/main/java/com/ai/assistance/operit/ui/common/composedsl/ToolPkgComposeDslScreen.kt', 703, 'ToolPkgComposeDslToolScreen', 'fun ToolPkgComposeDslToolScreen(', 'screen'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/packTool/ToolPkgComposeDslParser.kt', 21, 'ToolPkgComposeDslParser', 'fun parseRenderResult(', 'parser'],
    ['app/src/main/java/com/ai/assistance/operit/ui/common/composedsl/ToolPkgComposeDslScreen.kt', 2131, 'renderer 查找', 'composeDslGeneratedNodeRendererRegistry[normalizedType]', 'registry'],
    ['app/src/main/java/com/ai/assistance/operit/ui/common/composedsl/ToolPkgComposeDslGeneratedRegistry.kt', 7, '生成注册表', 'composeDslGeneratedNodeRendererRegistry', 'executor'],
  ],
  mcp: [
    ['app/src/main/java/com/ai/assistance/operit/core/tools/mcp/MCPToolExecutor.kt', 263, 'MCPToolExecutor.invoke', 'override fun invoke(tool: AITool)', 'caller'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/mcp/MCPToolExecutor.kt', 279, '会话获取', 'mcpManager.getOrCreateSession(serverName)', 'manager'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/mcp/McpRuntimeSession.kt', 38, 'McpRuntimeSession', 'interface McpRuntimeSession', 'port'],
    ['app/src/main/java/com/ai/assistance/operit/data/mcp/plugins/RemoteMcpRuntimeSession.kt', 117, 'RemoteMcpRuntimeSession.callTool', 'override suspend fun callTool(', 'session'],
    ['app/src/main/java/com/ai/assistance/operit/data/mcp/plugins/RemoteMcpRuntimeSession.kt', 65, 'SSE transport', 'SseClientTransport(', 'executor'],
  ],
  workflow: [
    ['app/src/main/java/com/ai/assistance/operit/core/workflow/WorkflowWorker.kt', 39, 'WorkflowWorker', 'repository.triggerWorkflow(workflowId, triggerNodeId)', 'caller'],
    ['app/src/main/java/com/ai/assistance/operit/data/repository/WorkflowRepository.kt', 635, 'WorkflowRepository', 'WorkflowExecutor(context)', 'repository'],
    ['app/src/main/java/com/ai/assistance/operit/core/workflow/WorkflowExecutor.kt', 527, 'WorkflowExecutor.executeWorkflow', 'suspend fun executeWorkflow(', 'executor'],
    ['app/src/main/java/com/ai/assistance/operit/core/workflow/WorkflowExecutor.kt', 1275, '节点工具调用', 'toolHandler.executeTool(tool)', 'dispatcher'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/AIToolHandler.kt', 363, 'AIToolHandler.executeTool', 'fun executeTool(tool: AITool)', 'tool-runtime'],
    ['app/src/main/java/com/ai/assistance/operit/core/workflow/WorkflowScheduler.kt', 54, 'WorkflowScheduler', 'fun scheduleWorkflow(workflow: Workflow)', 'scheduler'],
  ],
  browser: [
    ['app/src/main/java/com/ai/assistance/operit/core/tools/ToolRegistration.kt', 1241, '浏览器工具注册', 'ToolGetter.getBrowserSessionTools(context).invoke(tool)', 'caller'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/ToolGetter.kt', 111, 'ToolGetter', 'fun getBrowserSessionTools(context: Context): StandardBrowserSessionTools', 'factory'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/standard/StandardBrowserSessionTools.kt', 251, 'StandardBrowserSessionTools.invoke', 'override fun invoke(tool: AITool)', 'session'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/websession/browser/WebSessionWebViewHost.kt', 26, 'WebSessionWebViewHost', 'fun attachContainer(target: FrameLayout)', 'host'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/websession/browser/BrowserWebViewSupport.kt', 844, 'WebView 导航', 'session.webView.loadUrl(targetUrl, headers)', 'executor'],
  ],
  'file-provider': [
    ['app/src/main/AndroidManifest.xml', 168, 'Provider 声明', '.provider.WorkspaceDocumentsProvider', 'registration'],
    ['app/src/main/java/com/ai/assistance/operit/provider/WorkspaceDocumentsProvider.kt', 20, 'WorkspaceDocumentsProvider', 'class WorkspaceDocumentsProvider', 'provider'],
    ['app/src/main/java/com/ai/assistance/operit/provider/WorkspaceDocumentsProvider.kt', 119, 'openDocument', 'override fun openDocument(', 'entry'],
    ['app/src/main/java/com/ai/assistance/operit/provider/WorkspaceDocumentsProvider.kt', 131, 'PFD 打开', 'ParcelFileDescriptor.open(file, accessMode)', 'executor'],
  ],
  navigation: [
    ['app/src/main/java/com/ai/assistance/operit/ui/main/OperitApp.kt', 115, 'OperitApp 导航装配', 'AppRouteCatalog.build(context)', 'caller'],
    ['app/src/main/java/com/ai/assistance/operit/ui/main/navigation/AppRouteCatalog.kt', 16, 'AppRouteCatalog.build', 'fun build(context: Context)', 'catalog'],
    ['app/src/main/java/com/ai/assistance/operit/ui/main/navigation/AppRouteCatalog.kt', 61, '路由表装配', 'ScreenRouteRegistry.hostRouteSpecs(context)', 'registry'],
    ['app/src/main/java/com/ai/assistance/operit/ui/main/screens/ScreenRouteRegistry.kt', 87, 'ScreenRouteRegistry', 'object ScreenRouteRegistry', 'routes'],
    ['app/src/main/java/com/ai/assistance/operit/ui/main/navigation/RouteBackGuard.kt', 18, '返回守卫', 'fun register(routeInstanceId: String', 'guard'],
    ['app/src/main/java/com/ai/assistance/operit/ui/main/layout/PhoneLayout.kt', 56, 'PhoneLayout', 'fun PhoneLayout(', 'layout'],
    ['app/src/main/java/com/ai/assistance/operit/ui/main/layout/TabletLayout.kt', 39, 'TabletLayout', 'fun TabletLayout(', 'executor'],
  ],
  'stream-markdown': [
    ['app/src/main/java/com/ai/assistance/operit/ui/common/displays/MarkdownTextComposable.kt', 37, 'MarkdownTextComposable', 'StreamMarkdownRenderer(', 'caller'],
    ['app/src/main/java/com/ai/assistance/operit/ui/common/markdown/StreamMarkdownRenderer.kt', 381, 'StreamMarkdownRenderer', 'fun StreamMarkdownRenderer(', 'renderer'],
    ['app/src/main/java/com/ai/assistance/operit/ui/common/markdown/MarkdownPlainTextRenderer.kt', 116, '原生 splitter 调用', 'NativeMarkdownSplitter.parseInlineToStableNodes(raw)', 'splitter'],
    ['app/src/main/java/com/ai/assistance/operit/util/streamnative/NativeMarkdownSplitter.kt', 41, 'NativeMarkdownSplitter', 'object NativeMarkdownSplitter', 'jni'],
    ['app/src/main/java/com/ai/assistance/operit/util/streamnative/NativeMarkdownSplitter.kt', 47, 'nativeCreateBlockSession', 'private external fun nativeCreateBlockSession', 'executor'],
  ],
  voice: [
    ['app/src/main/java/com/ai/assistance/operit/ui/floating/voice/SpeechInteractionManager.kt', 58, 'SpeechInteractionManager', 'SpeechServiceFactory.getInstance(context)', 'caller'],
    ['app/src/main/java/com/ai/assistance/operit/api/speech/SpeechServiceFactory.kt', 181, 'SpeechServiceFactory.getInstance', 'fun getInstance(', 'factory'],
    ['app/src/main/java/com/ai/assistance/operit/api/speech/SpeechServiceFactory.kt', 134, '本地 provider 选择', 'SpeechServiceType.SHERPA_NCNN -> SherpaSpeechProvider(appContext)', 'selector'],
    ['app/src/main/java/com/ai/assistance/operit/api/speech/SherpaSpeechProvider.kt', 188, 'SherpaNcnn 构造', 'SherpaNcnn(', 'executor'],
    ['app/src/main/java/com/ai/assistance/operit/api/speech/OnnxSileroVad.kt', 15, 'OnnxSileroVad', 'class OnnxSileroVad(', 'vad'],
    ['app/src/main/java/com/ai/assistance/operit/api/voice/VoiceServiceFactory.kt', 8, 'VoiceServiceFactory', 'object VoiceServiceFactory', 'tts'],
  ],
  'doc-ocr': [
    ['app/src/main/java/com/ai/assistance/operit/services/core/AttachmentDelegate.kt', 642, '附件 OCR 调用', 'OCRUtils.recognizeText(', 'caller'],
    ['app/src/main/java/com/ai/assistance/operit/util/OCRUtils.kt', 242, 'OCRUtils.recognizeText', 'suspend fun recognizeText(', 'api'],
    ['app/src/main/java/com/ai/assistance/operit/util/OCRUtils.kt', 54, 'getRecognizer', 'private fun getRecognizer(', 'recognizer'],
    ['app/src/main/java/com/ai/assistance/operit/util/OCRUtils.kt', 223, 'ML Kit process', '.process(image)', 'executor'],
    ['app/src/main/java/com/ai/assistance/operit/util/DocumentConversionUtil.kt', 35, 'DocumentConversionUtil', 'object DocumentConversionUtil', 'converter'],
  ],
  'local-inference': [
    ['app/src/main/java/com/ai/assistance/operit/api/chat/llmprovider/LlamaProvider.kt', 118, 'LlamaProvider', 'if (!LlamaSession.isAvailable())', 'caller'],
    ['llm/llama/src/main/java/com/ai/assistance/llama/LlamaSession.kt', 43, 'LlamaSession', 'return LlamaSession(ptr)', 'session'],
    ['llm/llama/src/main/java/com/ai/assistance/llama/LlamaNative.kt', 3, 'LlamaNative', 'object LlamaNative', 'jni'],
    ['llm/llama/src/main/cpp/llama_jni_stub.cpp', 206, 'nativeCreateSession', 'Java_com_ai_assistance_llama_LlamaNative_nativeCreateSession', 'executor'],
    ['app/src/main/java/com/ai/assistance/operit/api/chat/llmprovider/MNNProvider.kt', 192, 'MNNProvider', 'llmSession = MNNLlmSession.create(', 'mnn-caller'],
    ['llm/mnn/src/main/java/com/ai/assistance/mnn/MNNLlmSession.kt', 11, 'MNNLlmSession', 'class MNNLlmSession', 'mnn-executor'],
  ],
  shower: [
    ['app/src/main/java/com/ai/assistance/operit/core/tools/agent/PhoneAgent.kt', 196, 'PhoneAgent', 'ShowerServerManager.ensureServerStarted(context)', 'caller'],
    ['showerclient/src/main/java/com/ai/assistance/showerclient/ShowerServerManager.kt', 32, 'ShowerServerManager', 'suspend fun ensureServerStarted(', 'server'],
    ['app/src/main/java/com/ai/assistance/operit/core/tools/agent/PhoneAgent.kt', 217, 'PhoneAgent 虚拟屏', 'ShowerController.ensureDisplay(agentId, context', 'client'],
    ['showerclient/src/main/java/com/ai/assistance/showerclient/ShowerController.kt', 34, 'getBinder', 'private suspend fun getBinder(', 'binder'],
    ['showerclient/src/main/java/com/ai/assistance/showerclient/ShowerController.kt', 279, 'ensureDisplay', 'service.ensureDisplay(targetWidth, targetHeight, dpi, bitrate)', 'executor'],
  ],
  integrations: [
    ['app/src/main/java/com/ai/assistance/operit/integrations/intent/ExternalChatReceiver.kt', 45, 'ExternalChatReceiver.onReceive', 'override fun onReceive(', 'caller'],
    ['app/src/main/java/com/ai/assistance/operit/integrations/intent/ExternalChatReceiver.kt', 54, '外部请求执行', 'ExternalChatRequestExecutor(context.applicationContext).execute(', 'executor'],
    ['app/src/main/java/com/ai/assistance/operit/integrations/http/ExternalChatHttpServer.kt', 43, 'ExternalChatHttpServer', 'class ExternalChatHttpServer(', 'http'],
    ['app/src/main/java/com/ai/assistance/operit/data/repository/WorkflowRepository.kt', 840, 'Tasker 事件', 'suspend fun triggerWorkflowsByTaskerEvent', 'tasker'],
    ['app/src/main/java/com/ai/assistance/operit/integrations/a2a/A2aTaskManager.kt', 64, 'A2aTaskManager', 'class A2aTaskManager(', 'a2a'],
  ],
};

// 每个 Operit 包必须显式归入采用、部分采用、替换或不采用；最长前缀命中。
const packageRules = [
  ['api/chat/llmprovider', 'replaced', [], 'provider 协议实现由 S3.04–S3.08 依桌面基线重写，仅作对拍参考'],
  ['api/chat/enhance', 'replaced', [], '消息增强管线由 S3.10 块增量与 S3.12 上下文编译承载'],
  ['api/chat/library', 'replaced', [], '聊天库由 S3.01 仓储与 S3.15 变体状态机承载'],
  ['api/chat', 'partial', ['foreground-service'], 'AIForegroundService 是前台职责采用单元证据；其余聊天运行时由 S3 重写'],
  ['api/speech', 'adopted', ['voice'], 'SpeechServiceFactory 及 STT/VAD provider 是 voice 采用单元证据'],
  ['api/voice', 'adopted', ['voice'], 'TTS provider 工厂与队列是 voice 采用单元证据'],
  ['core/application', 'replaced', [], '应用初始化由 S1.02 进程感知 AppGraph 承载'],
  ['core/avatar', 'rejected', ['avatar'], '产品无虚拟形象需求'],
  ['core/chat', 'replaced', [], '消息与 prompt 运行钩子由 S3.01/S3.12 重写'],
  ['core/config', 'replaced', [], '系统提示与功能提示由 S3.12 上下文编译承载'],
  ['core/performance', 'replaced', [], '性能观测由 S1.27 与 S9 测量方案统一'],
  ['core/subpack', 'rejected', ['subpack'], 'APK/EXE 工具链不在产品范围'],
  ['core/tools/agent', 'adopted', ['shower'], 'PhoneAgent 是 Shower 采用单元的设备侧证据'],
  ['core/tools/calculator', 'replaced', [], '工具目录由 S3.16 依桌面注册表建立'],
  ['core/tools/climode', 'replaced', [], 'CLI 模式并入 S6 终端与 S3.26 工具'],
  ['core/tools/condition', 'replaced', [], '条件执行由 S3.18 工具循环承载'],
  ['core/tools/defaultTool', 'adopted', ['terminal-kernel', 'root-standard-exec', 'browser'], '终端/Shell/浏览器工具执行器是三个采用单元证据'],
  ['core/tools/javascript', 'adopted', ['quickjs'], 'JsEngine 是 QuickJS 采用单元的宿主侧证据'],
  ['core/tools/mcp', 'adopted', ['mcp'], 'McpRuntimeSession 与 MCPToolExecutor 是 mcp 采用单元证据'],
  ['core/tools/packTool', 'adopted', ['toolpkg', 'compose-dsl'], 'Parser/Loader/DSL parser 是 toolpkg 与 compose-dsl 证据'],
  ['core/tools/skill', 'replaced', [], '技能加载由 S3.20 依桌面 manifest 实现'],
  ['core/tools/system', 'adopted', ['shizuku', 'root-standard-exec', 'terminal-kernel'], 'ShizukuAuthorizer/ShellExecutor/Terminal 是三个采用单元证据'],
  ['core/tools', 'adopted', ['terminal-kernel', 'root-standard-exec', 'browser', 'mcp'], 'ToolRegistration/AIToolHandler 是工具注册与执行的公共接缝'],
  ['core/workflow', 'adopted', ['workflow'], '工作流执行器与调度是 workflow 采用单元证据'],
  ['data/announcement', 'rejected', ['announcement'], '不接入外部公告源'],
  ['data/annualreport', 'rejected', ['annual-report'], '运营内容不在产品范围'],
  ['data/api', 'rejected', ['codex-auth'], 'provider 认证由 S3.02–S3.08 实现'],
  ['data/backup', 'rejected', ['backup-room'], '备份恢复由 S2.09–S2.15 统一'],
  ['data/collects', 'replaced', [], '模型定价/能力采集由 S3.11/S3.14 依桌面目录实现'],
  ['data/converter', 'replaced', [], '聊天导入导出由 S7.05 格式单元实现'],
  ['data/dao', 'replaced', [], 'Room DAO 由 S2.01 原生 SQLite 仓储替代'],
  ['data/db', 'replaced', [], 'Room/ObjectBox 由 S2.01 DatabaseRegistry 替代'],
  ['data/exporter', 'replaced', [], '导出由 S7.05 实现'],
  ['data/mcp', 'adopted', ['mcp'], 'MCP 配置仓库与本地服务器是 mcp 采用单元证据'],
  ['data/mnn', 'adopted', ['local-inference'], '模型下载管理是本地推理采用单元证据'],
  ['data/model', 'rejected', ['room-model'], 'Room 实体不可复用，领域模型由 S3.01 建立'],
  ['data/preferences', 'rejected', ['preferences'], '设置源由 S0.06/S0.23 定义'],
  ['data/recovery', 'replaced', [], '健康检查与恢复由 S2.03/S2.19 承载'],
  ['data/repository', 'partial', ['workflow'], 'WorkflowRepository 被采用；Avatar/ChatHistory/Memory 仓库不采用'],
  ['data/security', 'replaced', [], '插件信任与拒绝名单由 S6.35 与 S0.24 承载'],
  ['data/skill', 'replaced', [], '技能仓库由 S3.20 实现'],
  ['data/stats', 'rejected', ['token-stats'], '用量口径由 S3.11 统一'],
  ['data/updates', 'rejected', ['updates'], '更新由发布合同统一'],
  ['integrations/a2a', 'adopted', ['integrations'], 'A2A 任务入口是外部集成采用单元证据'],
  ['integrations/externalchat', 'adopted', ['integrations'], '外部聊天执行器是外部集成采用单元证据'],
  ['integrations/http', 'adopted', ['integrations'], '外部 HTTP 服务与 SSE 写出是外部集成采用单元证据'],
  ['integrations/intent', 'adopted', ['integrations'], '外部 Intent 广播接收是外部集成采用单元证据'],
  ['integrations/tasker', 'adopted', ['integrations'], 'Tasker 触发是外部集成采用单元证据'],
  ['plugins/toolpkg', 'partial', ['toolpkg', 'compose-dsl'], 'ToolPkg 宿主桥接采用；聊天钩子桥不采用'],
  ['plugins', 'rejected', ['plugin-hooks'], 'Operit 插件钩子体系与 dstu UI contribution 模型不兼容'],
  ['provider', 'adopted', ['file-provider'], 'DocumentsProvider 三实现是文件 Provider 采用单元证据'],
  ['services/assistant', 'rejected', ['assistant-service'], '系统级语音助手接入不在产品范围'],
  ['services/core', 'replaced', [], '聊天委托由 S3 聊天纵切重写'],
  ['services/floating', 'adopted', ['floating-window'], '浮窗管理与会话是悬浮窗采用单元证据'],
  ['services/notification', 'rejected', ['notification-listener'], '不读取第三方通知'],
  ['services', 'partial', ['floating-window'], '仅 FloatingChatService 采用为浮窗证据；聊天服务内核与 UI 桥由 S3 重写，其余子包已单独归类'],
  ['ui/common/animations', 'replaced', [], '动效由 DstuTheme（S5.01）令牌驱动'],
  ['ui/common/browser', 'adopted', ['browser'], '浏览器通用组件是 browser 采用单元证据'],
  ['ui/common/composedsl', 'adopted', ['compose-dsl'], 'Compose DSL 渲染宿主是采用单元证据'],
  ['ui/common/displays', 'adopted', ['stream-markdown'], 'Markdown 显示组合是流式内容采用单元证据'],
  ['ui/common/icons', 'replaced', [], '图标由 S0.12 品牌基线与 S5.04 生成'],
  ['ui/common/input', 'replaced', [], '输入适配由 S5.11 IME 单元实现'],
  ['ui/common/markdown', 'adopted', ['stream-markdown'], '流式 Markdown 渲染器族是采用单元证据'],
  ['ui/common', 'partial', ['navigation', 'stream-markdown'], 'NavItem 作导航参考、Markdown 显示采用；OperitUtilityTheme 与 WaveVisualizer 不采用'],
  ['ui/components', 'replaced', [], '通用组件由 S5.02/S5.03 依桌面设计令牌实现'],
  ['ui/error', 'replaced', [], '错误与诊断页由 S2.28 实现'],
  ['ui/features/about', 'replaced', [], '关于页由 S5.04 品牌资源与设置壳承载'],
  ['ui/features/agreement', 'rejected', ['demo'], '协议页属 Operit 运营内容'],
  ['ui/features/announcement', 'rejected', ['announcement'], '公告页不采用'],
  ['ui/features/annualreport', 'rejected', ['annual-report'], '年度报告不采用'],
  ['ui/features/assistant', 'rejected', ['avatar'], '助手形象配置不采用'],
  ['ui/features/chat', 'replaced', [], '聊天页面由 S3.27 依桌面设计实现'],
  ['ui/features/codex', 'rejected', ['codex-auth'], 'Codex 账号页不采用'],
  ['ui/features/demo', 'rejected', ['demo'], '演示与诊断面板不采用'],
  ['ui/features/github', 'rejected', ['codex-auth'], 'GitHub 市场页不采用'],
  ['ui/features/help', 'rejected', ['demo'], '帮助页由 dstu 设置壳承载'],
  ['ui/features/memory', 'replaced', [], '记忆页面由 S3.24 实现'],
  ['ui/features/packages', 'partial', ['toolpkg'], '包管理页面交互参考，渲染改用 DstuTheme（S6.35）'],
  ['ui/features/performance', 'replaced', [], '性能页由 S9 测量与 S2.28 诊断承载'],
  ['ui/features/permission', 'rejected', ['permission-ui'], '权限引导由 S2.23 审批与 S5 组件实现'],
  ['ui/features/settings', 'replaced', [], '设置分区由 S7.35 依 S0.06 清单实现'],
  ['ui/features/startup', 'replaced', [], '启动页由 S1.12 导航壳承载'],
  ['ui/features/token', 'rejected', ['token-stats'], 'Token 页不采用'],
  ['ui/features/tokenstats', 'rejected', ['token-stats'], '用量统计不采用'],
  ['ui/features/toolbox', 'partial', ['terminal-ui'], 'TerminalToolScreen 是终端界面入口；其余工具箱页面不采用'],
  ['ui/features/update', 'rejected', ['updates'], '更新页不采用'],
  ['ui/features/websession', 'adopted', ['browser'], '浏览器会话 UI 是 browser 采用单元证据'],
  ['ui/features/workflow', 'partial', ['workflow'], '工作流编辑 UI 交互参考，渲染改用 DstuTheme'],
  ['ui/floating', 'adopted', ['floating-window'], '浮窗聊天 UI 是悬浮窗采用单元证据'],
  ['ui/main', 'adopted', ['navigation'], '自适应布局与路由目录是导航采用单元证据'],
  ['ui/permissions', 'rejected', ['permission-ui'], '权限弹窗不移植'],
  ['ui/recovery', 'replaced', [], '恢复页由 S2.10/S2.11 恢复计划承载'],
  ['ui/theme', 'rejected', ['theme'], '视觉由 DstuTheme 决定'],
  ['util/exceptions', 'replaced', [], '错误类型由 S1.03 核心端口定义'],
  ['util/markdown', 'partial', ['stream-markdown'], 'MarkdownProcessor 作流式渲染参考；不复制实现'],
  ['util/ripgrep', 'rejected', ['ripgrep'], '不引入额外原生检索二进制'],
  ['util/stream', 'adopted', ['stream-markdown'], '可修订文本流是流式内容采用单元证据'],
  ['util/streamnative', 'adopted', ['stream-markdown'], '原生 splitter 是流式内容采用单元证据'],
  ['util/vector', 'rejected', ['vector-index'], '向量索引由 S4.25 按桌面 profile 实现'],
  ['util', 'replaced', [], '通用工具由 dstu core/util 与 S2.28 日志诊断替代'],
  ['widget', 'rejected', ['desktop-widget'], '不引入 AppWidget 双宿主'],
];

// 单文件级采用：同一目录内其余文件按目录规则处理。
const fileRules = [
  ['app/src/main/java/com/ai/assistance/operit/util/OCRUtils.kt', 'adopted', ['doc-ocr'], 'OCR 工具是文档/OCR 采用单元证据'],
  ['app/src/main/java/com/ai/assistance/operit/util/DocumentConversionUtil.kt', 'adopted', ['doc-ocr'], '文档转换工具是文档/OCR 采用单元证据'],
  ['app/src/main/java/com/ai/assistance/operit/util/LatexMathMlConverter.kt', 'adopted', ['stream-markdown'], '公式转换经 QuickJS 渲染，属流式内容证据'],
  ['app/src/main/java/com/ai/assistance/operit/util/ToolPkgJsAstMinifier.kt', 'adopted', ['toolpkg'], 'ToolPkg 脚本压缩是包解析证据'],
];

// terminal 子模块（OperitTerminalCore）的包级决策，覆盖根级 android 包之外的源码。
const terminalRules = [
  ['src/androidTest', 'partial', ['terminal-kernel'], 'PtyIo 兼容性测试作为 S6.02 验收夹具参考，不直接搬运测试工程'],
  ['src/test', 'partial', ['terminal-kernel'], 'PtyChildProcess/SessionInputRouter/TrackedCommandProtocol 测试作为行为夹具参考'],
  ['src/main/java/com/ai/assistance/operit/terminal/view', 'adopted', ['terminal-ui'], 'ANSI 模拟器、Canvas 渲染与手势是终端界面采用单元证据'],
  ['src/main/java/com/ai/assistance/operit/terminal/main', 'adopted', ['terminal-ui'], 'TerminalScreen 与路由是终端界面采用单元证据'],
  ['src/main/java/com/ai/assistance/operit/terminal/ui', 'adopted', ['terminal-ui', 'dependency-mirror'], 'SetupScreen/Settings/TerminalHome 分属终端界面与依赖安装采用单元'],
  ['src/main/java/com/ai/assistance/operit/terminal/utils', 'partial', ['dependency-mirror'], 'SourceManager 采用；SSH/FTP/SFTP 服务在 S6.08 显式拆出后另行接入'],
  ['src/main/java/com/ai/assistance/operit/terminal/provider', 'partial', ['terminal-kernel'], 'LocalTerminalProvider 采用；SSH provider/文件系统在 S6.08 拆出'],
  ['src/main/java/com/ai/assistance/operit/terminal/service', 'partial', ['terminal-kernel'], 'TerminalService AIDL 绑定保留作进程边界参考，dstu 以 AppGraph 单进程装配'],
  ['src/main/java/com/ai/assistance/operit/terminal', 'adopted', ['terminal-kernel'], 'PTY/会话/输入路由/命令跟踪是终端内核采用单元证据'],
  ['src/main/jni', 'adopted', ['terminal-kernel'], 'forkpty JNI 与 CMake 是终端内核构建来源'],
  ['src/main/assets', 'adopted', ['dependency-mirror'], 'Ubuntu noble rootfs 与 setup 脚本是依赖安装来源，资源登记见 S0.14'],
  ['src/main/aidl', 'partial', ['terminal-kernel'], 'ITerminalService 等 AIDL 作为跨进程参考；dstu 使用 Kotlin 端口'],
  ['src/main/res', 'replaced', [], '字体与字符串由 S0.12 品牌基线和 S5 资源替代'],
  ['src/main', 'partial', ['terminal-kernel'], 'AndroidManifest 服务声明并入 dstu 清单合同'],
];

function terminalPackages() {
  const files = git(terminalRoot, 'ls-files', 'src').split('\n').filter(Boolean);
  const found = new Map();
  for (const file of files) {
    const marker = 'src/main/java/com/ai/assistance/operit/terminal';
    let id;
    if (file.startsWith(marker)) {
      const parts = file.slice(marker.length + 1).split('/');
      parts.pop();
      id = parts.length === 0 ? marker : `${marker}/${parts.slice(0, parts.length === 1 ? 1 : 1).join('/')}`;
    } else {
      const parts = file.split('/');
      parts.pop();
      id = parts.join('/');
    }
    const entry = found.get(id) ?? { id, files: 0, kotlin: 0, sample: file };
    entry.files += 1;
    if (file.endsWith('.kt')) entry.kotlin += 1;
    found.set(id, entry);
  }
  return found;
}

function decideTerminal(id) {
  const hit = terminalRules.filter(([prefix]) => id === prefix || id.startsWith(`${prefix}/`)).sort((a, b) => b[0].length - a[0].length)[0];
  assert.ok(hit, `terminal package ${id} has no adoption decision`);
  return hit;
}

// rootfs 实际代号必须与安装脚本写入的 apt 代号一致；不采信文件名。
function rootfsFacts() {
  const asset = 'terminal/src/main/assets/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz';
  const path = resolve(operitRoot, asset);
  assert.ok(existsSync(path), `${asset}: rootfs asset`);
  const readEntry = name => execFileSync('tar', ['-xJOf', path, name], { encoding: 'utf8', maxBuffer: 1 << 20 });
  const lsb = readEntry('ubuntu-noble-aarch64/etc/lsb-release');
  const codename = lsb.match(/DISTRIB_CODENAME=(\S+)/)[1];
  const release = lsb.match(/DISTRIB_RELEASE=(\S+)/)[1];
  const sources = readEntry('ubuntu-noble-aarch64/etc/apt/sources.list');
  const sourceCodename = sources.match(/ubuntu-ports\s+(\w+)\s+main/)[1];
  const installScript = read('terminal/src/main/java/com/ai/assistance/operit/terminal/TerminalManager.kt');
  const scriptedCodename = installScript.match(/deb \$\{aptSource\.url\} (\w+) main restricted/)[1];
  assert.equal(codename, sourceCodename, 'rootfs apt sources codename vs lsb');
  assert.equal(codename, scriptedCodename, 'rootfs codename vs install script');
  return {
    asset,
    bytes: readFileSync(path).length,
    sha256: sha256(readFileSync(path)),
    entries: execFileSync('tar', ['-tJf', path], { encoding: 'utf8', maxBuffer: 1 << 26 }).trim().split('\n').length,
    release,
    codename,
    aptCodename: sourceCodename,
    installScriptCodename: scriptedCodename,
    consistent: codename === sourceCodename && codename === scriptedCodename,
  };
}

function planUnits() {
  const text = readFileSync(resolve(root, planFile), 'utf8');
  const units = new Map();
  for (const match of text.matchAll(/^\| (S\d\.\d\d) \| ([^|]+) \|/gm)) units.set(match[1], match[2].trim());
  return units;
}

function sectionLine(text, needle) {
  const index = text.split('\n').findIndex(row => row.startsWith(needle));
  assert.ok(index >= 0, `plan section ${needle}`);
  return index + 1;
}

function checkEvidence(entry) {
  const path = entry.file;
  assert.ok(exists(path), `${path}: missing source file`);
  const source = read(path);
  const lines = source.split('\n');
  assert.ok(entry.line >= 1 && entry.line <= lines.length, `${path}:${entry.line} out of range`);
  const text = lines[entry.line - 1].trim();
  assert.ok(text.includes(entry.token), `${path}:${entry.line} lacks token ${entry.token} -> ${text}`);
  return { file: path, line: entry.line, symbol: entry.symbol, text, sha256: sha256(source) };
}

function desktopLine(file, token) {
  assert.ok(desktopExists(file), `desktop source ${file}`);
  const source = readDesktop(file);
  const line = source.split('\n').findIndex(row => row.includes(token)) + 1;
  assert.ok(line > 0, `${file}: missing ${token}`);
  return { file, line, text: token, sha256: sha256(source) };
}

function desktopSeamEvidence(seam) {
  const sources = seam.files.map(file => ({ file, sha256: sha256(readDesktop(file)) }));
  return {
    id: seam.id,
    owner: seam.owner,
    ownershipBasis: seam.ownershipBasis,
    relatedUnits: seam.relatedUnits,
    services: seam.services.map(([name, id, kind, unit, adapterOwner, dependency]) => ({ name, id, kind, contractUnit: unit, adapterOwner, dependency: dependency ?? null })),
    sources,
    android: seam.android,
  };
}

function brandPageEvidence(brand, adopted) {
  const pages = brand.operitPages.map(page => {
    const owners = adopted.filter(item => item.brandPages.includes(page.id));
    assert.ok(owners.length > 0, `${page.id}: expected an adoption owner`);
    const primary = owners.find(item => item.deliveryUnit === page.deliveryUnit) ?? owners.find(item => item.primaryUnit === page.deliveryUnit) ?? owners[0];
    return {
      id: page.id,
      zh: page.zh,
      en: page.en,
      route: page.route,
      deliveryUnit: page.deliveryUnit,
      owner: primary.id,
      ownerUnit: page.deliveryUnit,
      aliases: owners.filter(item => item.id !== primary.id).map(item => item.id),
      source: page.source?.file ?? null,
    };
  });
  return {
    file: brandFile,
    count: pages.length,
    snapshotUnit: brand.unit,
    sourceHashesIncluded: false,
    rule: 'page identity, copy, route and owning adoption unit are checked; brand hash values are owned by S0.12 and are not copied here',
    pages,
  };
}

function packages() {
  const files = git(operitRoot, 'ls-files', 'app/src/main/java/com/ai/assistance/operit').split('\n').filter(Boolean);
  const found = new Map();
  for (const file of files) {
    const parts = file.replace('app/src/main/java/com/ai/assistance/operit/', '').split('/');
    if (parts.length < 2) continue;
    parts.pop();
    const depth = ['ui/features', 'ui/common', 'core/tools', 'data/mcp', 'api/chat'].includes(parts.slice(0, 2).join('/')) ? 3 : 2;
    const id = parts.slice(0, depth).join('/');
    const entry = found.get(id) ?? { id, kotlin: 0, files: 0, sample: file };
    entry.kotlin += 1;
    entry.files += 1;
    found.set(id, entry);
  }
  return found;
}

function workbenchSeamEvidence(workbench, ownerForUnit) {
  const family = workbench.dynamicFamily;
  assert.equal(family.id, 'plugin-view', 'dynamic workbench family');
  assert.equal(family.prefix, 'plugin-view.', 'dynamic workbench prefix');
  assert.equal(family.staticGoldenMembership, false, 'dynamic family is not an official static app');
  assert.equal(family.singleton.instanceMode, 'single', 'dynamic family singleton');
  assert.equal(family.owner.replacement.includes('unregisters the old definition'), true, 'dynamic family replacement');
  const check = desktopLine('src/features/workbench/apps/plugin-views/register.tsx', "export const PLUGIN_VIEW_APP_PREFIX = 'plugin-view.';");
  assert.equal(family.prefix, 'plugin-view.', `${check.file}: prefix`);
  const host = family.nativeDelivery.map(item => {
    const owner = ownerForUnit(item.unit);
    return { ...item, owner, adopted: owner !== null, internalObligation: owner === null };
  });
  for (const item of host.filter(item => !item.adopted)) {
    assert.ok(['S6.43', 'S7.37'].includes(item.unit), `${item.unit}: later workbench obligation`);
    assert.ok(item.responsibility, `${item.unit}: responsibility`);
  }
  return {
    id: family.id,
    prefix: family.prefix,
    file: workbenchFile,
    staticGoldenMembership: family.staticGoldenMembership,
    singleton: family.singleton,
    source: check,
    hostOwner: host.find(item => item.unit === 'S6.34').owner,
    nativeDelivery: host,
    notAdopted: {
      origin: 'upstream',
      chain: 'dstu-node mobile profile / Capacitor / mobile.ts',
      rule: 'the frozen desktop profile and the host workbench are the adoption target; the upstream mobile chain is evidence only',
    },
  };
}

function nativeWorkbenchOwner(unit, adopted) {
  const owner = adopted.find(item => item.deliveryUnit === unit || item.primaryUnit === unit);
  return owner?.id ?? null;
}

function modules() {
  const modules = ['app', 'terminal', 'quickjs', 'llama', 'mnn', 'showerclient', 'dragonbones', 'fbx', 'mmd', 'web-chat', 'tools', 'examples', 'docs', 'ci', 'annual-report'];
  return modules.map(id => {
    const path = { llama: 'llm/llama', mnn: 'llm/mnn', dragonbones: 'avator/dragonbones', fbx: 'avator/fbx', mmd: 'avator/mmd' }[id] ?? id;
    const tracked = git(operitRoot, 'ls-files', path).split('\n').filter(Boolean);
    return { id, path, files: tracked.length, kotlin: tracked.filter(file => file.endsWith('.kt')).length };
  });
}

function selfChecks() {
  const rejectsFakeSymbol = (() => {
    try {
      checkEvidence({ file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/Pty.kt', line: 11, symbol: 'Pty', token: 'class NotPty' });
      return false;
    } catch { return true; }
  })();
  const rejectsWrongLine = (() => {
    try {
      checkEvidence({ file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/TerminalManager.kt', line: 1, symbol: 'TerminalManager', token: 'class TerminalManager' });
      return false;
    } catch { return true; }
  })();
  const rejectsUnknownPackage = (() => {
    try { decide('does/not/exist'); return false; } catch { return true; }
  })();
  const rejectsUnmappedTerminalPackage = (() => {
    try { decideTerminal('src/unmapped/package'); return false; } catch { return true; }
  })();
  const rejectsBrokenChain = (() => {
    try {
      checkEvidence({ file: 'terminal/src/main/java/com/ai/assistance/operit/terminal/Pty.kt', line: 11, symbol: 'Pty', token: 'createSubprocess' });
      return false;
    } catch { return true; }
  })();
  return { rejectsFakeSymbol, rejectsWrongLine, rejectsUnknownPackage, rejectsUnmappedTerminalPackage, rejectsBrokenChain };
}

function decide(id) {
  const hit = packageRules.filter(([prefix]) => id === prefix || id.startsWith(`${prefix}/`)).sort((a, b) => b[0].length - a[0].length)[0];
  assert.ok(hit, `package ${id} has no adoption decision`);
  return hit;
}

function collect() {
  const plan = planUnits();
  assert.ok(plan.size >= 300, `plan units ${plan.size}`);
  const lock = JSON.parse(readFileSync(resolve(root, lockFile), 'utf8'));
  const operit = lock.repositories.find(item => item.id === 'operit');
  const terminal = lock.repositories.find(item => item.id === 'terminal');
  assert.ok(operit && terminal, 'source lock repositories');
  assert.equal(git(operitRoot, 'rev-parse', 'HEAD'), operit.commit, 'Operit HEAD vs source lock');
  assert.equal(git(terminalRoot, 'rev-parse', 'HEAD'), terminal.commit, 'terminal HEAD vs source lock');
  const brand = JSON.parse(readFileSync(resolve(root, brandFile), 'utf8'));
  const domains = JSON.parse(readFileSync(resolve(root, domainsFile), 'utf8'));
  const workbench = JSON.parse(readFileSync(resolve(root, workbenchFile), 'utf8'));
  const classification = JSON.parse(readFileSync(resolve(root, classificationFile), 'utf8'));
  const desktop = lock.repositories.find(item => item.id === 'desktop');
  assert.ok(desktop, 'desktop repository in source lock');
  assert.equal(git(desktopRoot, 'rev-parse', 'HEAD'), desktop.commit, 'desktop HEAD vs source lock');
  assert.equal(domains.sourceLock.desktopCommit, desktop.commit, 'S0.05 desktop commit vs source lock');
  assert.equal(domains.adoption.desktopCommit, desktop.commit, 'S0.05 adoption commit vs source lock');
  assert.equal(domains.adoption.upstreamMobileChain, 'not-adopted', 'upstream mobile chain');
  assert.equal(domains.coverage.pluginDirectories, domains.plugins.length, 'S0.05 domain directory count');
  assert.equal(domains.coverage.productionFiles, domains.sourceIndex.length, 'S0.05 indexed files');
  assert.equal(workbench.unit, 'S0.04', 'workbench unit');
  assert.equal(workbench.dynamicFamily.staticGoldenMembership, false, 'dynamic family golden membership');
  const brandIds = new Set(brand.operitPages.map(page => page.id));
  const domainIds = new Set(domains.plugins.map(plugin => plugin.id));
  const planText = readFileSync(resolve(root, planFile), 'utf8');
  const sections = { section31: sectionLine(planText, '### 3.1 首批直接移植'), section32: sectionLine(planText, '### 3.2 按领域顺序接入'), section33: sectionLine(planText, '### 3.3 移植执行规则') };
  const found = packages();
  const terminalFound = terminalPackages();
  const rootfs = rootfsFacts();
  const inventory = modules();
  const rejectedIds = new Set(rejected.map(item => item[0]));

  const adopted = [...section31.map(row => ['3.1', row]), ...section32.map(row => ['3.2', row])].map(([section, [id, capability, sourcePath, method, primaryUnit, deliveryUnit, secondaryUnits]]) => {
    assert.ok(plan.has(deliveryUnit), `${id}: delivery unit ${deliveryUnit}`);
    assert.ok(plan.has(primaryUnit), `${id}: primary unit ${primaryUnit}`);
    for (const unitId of secondaryUnits) assert.ok(plan.has(unitId), `${id}: secondary unit ${unitId}`);
    assert.ok(['direct-port', 'adapter', 'reimplement'].includes(method), `${id}: method ${method}`);
    const chainKeys = new Set(chains[id].map(([file, line]) => `${file}:${line}`));
    const chain = evidence[id].filter(entry => !chainKeys.has(`${entry.file}:${entry.line}`)).map(checkEvidence);
    const callChain = chains[id].map(([file, line, symbol, token, stage]) => {
      const step = checkEvidence({ file, line, symbol, token });
      return { stage, ...step };
    });
    assert.ok(callChain.length >= 4, `${id}: call chain too short`);
    assert.ok(['caller', 'registration'].includes(callChain[0].stage), `${id}: chain must start at a caller or system registration`);
    assert.ok(terminalStages.includes(callChain[callChain.length - 1].stage), `${id}: chain must end at an executor, got ${callChain[callChain.length - 1].stage}`);
    for (let index = 1; index < callChain.length; index++) assert.notEqual(`${callChain[index].file}:${callChain[index].line}`, `${callChain[index - 1].file}:${callChain[index - 1].line}`, `${id}: chain step repeats`);
    for (let index = 1; index < chain.length; index++) assert.notEqual(`${chain[index].file}:${chain[index].line}`, `${chain[index - 1].file}:${chain[index - 1].line}`, `${id}: chain step repeats`);
    for (const page of relatedBrandPages[id]) assert.ok(brandIds.has(page), `${id}: brand page ${page}`);
    for (const domain of relatedDomains[id]) assert.ok(domainIds.has(domain), `${id}: domain ${domain}`);
    assert.ok(exists(sourcePath), `${id}: source path ${sourcePath}`);
    assert.ok(adapters[id].every(name => ['AppGraph', 'TaskSupervisor', 'CapabilityBroker', 'ResourceStore', 'DstuTheme'].includes(name)), `${id}: adapter names`);
    assert.ok(risks[id].length >= 1, `${id}: risk`);
    return {
      id, section, capability, sourcePath, method, primaryUnit, deliveryUnit, secondaryUnits,
      source: { commit: operit.commit, terminalCommit: terminal.commit, module: sourcePath.split('/')[0] },
      evidence: chain,
      callChain: callChain.map(step => `${step.stage}: ${step.file}:${step.line} ${step.symbol}`),
      callChainSteps: callChain,
      value: value[id],
      adapters: adapters[id],
      dependencies: dependencies[id],
      persistence: persistence[id],
      executionIdentity: executionIdentity[id],
      owner: ownerRoles[id],
      acceptance: acceptance[id],
      risks: risks[id],
      brandPages: relatedBrandPages[id],
      domains: relatedDomains[id],
    };
  });
  assert.equal(adopted.length, 21, 'adopted units');

  const rejectedRows = rejected.map(([id, capability, sourcePath, modulePath, decision, reason]) => {
    assert.ok(exists(sourcePath), `${id}: rejected source ${sourcePath}`);
    return { id, capability, sourcePath, modulePath, decision, reason, scope: 'product-scope' };
  });
  assert.equal(rejectedRows.length, 23, 'rejected modules');

  const adoptedIds = new Set(adopted.map(item => item.id));
  const domainServices = new Set(domains.services.map(service => service.id));
  const desktopEvidence = desktopSeams.map(seam => {
    assert.ok(adoptedIds.has(seam.owner), `${seam.id}: owner ${seam.owner}`);
    for (const unit of seam.relatedUnits) assert.ok(plan.has(unit), `${seam.id}: related unit ${unit}`);
    for (const [, serviceId, , unit, adapterOwner, dependency] of seam.services) {
      const service = domains.services.find(item => item.id === serviceId);
      assert.ok(service, `${seam.id}: S0.05 service ${serviceId}`);
      assert.equal(service.deliveryUnit, unit, `${seam.id}: ${serviceId} delivery unit`);
      for (const dependencyUnit of dependency?.split('/') ?? []) assert.ok(plan.has(dependencyUnit), `${seam.id}: ${serviceId} dependency ${dependencyUnit}`);
      assert.ok(adoptedIds.has(adapterOwner), `${seam.id}: adapter owner ${adapterOwner}`);
    }
    return desktopSeamEvidence(seam);
  });
  const workbenchEvidence = workbenchSeamEvidence(workbench, unit => nativeWorkbenchOwner(unit, adopted));
  const dynamicOwner = adopted.find(item => item.id === workbenchEvidence.hostOwner);
  assert.equal(dynamicOwner?.deliveryUnit, 'S6.34', 'dynamic family render owner must deliver S6.34');
  assert.ok(workbenchEvidence.nativeDelivery.filter(item => !item.adopted).every(item => item.internalObligation), 'later workbench obligations cannot claim adoption');

  const moduleRows = [...found.values()].sort((a, b) => a.id.localeCompare(b.id)).map(item => {
    const [prefix, decision, owners, reason] = decide(item.id);
    for (const owner of owners) {
      if (decision === 'rejected') assert.ok(rejectedIds.has(owner), `${item.id}: rejection ${owner}`);
      else assert.ok(adoptedIds.has(owner), `${item.id}: owner ${owner}`);
    }
    assert.ok(['adopted', 'partial', 'replaced', 'rejected'].includes(decision), `${item.id}: decision ${decision}`);
    return { ...item, rule: prefix, decision, owners, reason };
  });
  const terminalRows = [...terminalFound.values()].sort((a, b) => a.id.localeCompare(b.id)).map(item => {
    const [prefix, decision, owners, reason] = decideTerminal(item.id);
    for (const owner of owners) assert.ok(adoptedIds.has(owner), `${item.id}: owner ${owner}`);
    return { ...item, module: 'terminal', rule: prefix, decision, owners, reason };
  });
  const fileRows = fileRules.map(([path, decision, owners, reason]) => {
    assert.ok(exists(path), `${path}: missing file rule target`);
    for (const owner of owners) assert.ok(adoptedIds.has(owner), `${path}: owner ${owner}`);
    return { id: path, decision, owners, reason };
  });
  const usedRules = new Set(moduleRows.map(item => item.rule));
  const deadRules = packageRules.map(rule => rule[0]).filter(prefix => !usedRules.has(prefix));
  assert.deepEqual(deadRules, [], 'unused package rules');
  const usedTerminalRules = new Set(terminalRows.map(item => item.rule));
  assert.deepEqual(terminalRules.map(rule => rule[0]).filter(prefix => !usedTerminalRules.has(prefix)), [], 'unused terminal rules');
  for (const owner of adoptedIds) {
    const covered = moduleRows.some(item => item.owners.includes(owner))
      || terminalRows.some(item => item.owners.includes(owner))
      || fileRows.some(item => item.owners.includes(owner));
    assert.ok(covered, `adopted unit ${owner} has no package`);
  }

  const pageCoverage = [...brandIds].filter(id => !adopted.some(item => item.brandPages.includes(id)));
  assert.deepEqual(pageCoverage, [], 'every Operit brand page maps to an adoption unit');

  // The adopted Operit entries intentionally do not own every desktop domain.
  // Report unowned domains as a fact instead of claiming full domain coverage.
  const ownedDomains = new Set(adopted.flatMap(item => item.domains));
  const missingDomains = [...domainIds].filter(id => !ownedDomains.has(id));
  assert.equal(domainIds.size, domains.coverage.pluginDirectories, 'all S0.05 domains counted');
  const brandEvidence = brandPageEvidence(brand, adopted);

  // 内核归属：真实终端在 terminal 子模块，app 下同名对象只查外置包。
  assert.ok(exists(terminalKernelFile), 'terminal kernel file');
  assert.ok(exists(externalPackFile), 'external package manager file');
  assert.ok(read(terminalKernelFile).includes('class TerminalManager'), 'terminal kernel symbol');
  assert.ok(!read(externalPackFile).includes('class TerminalManager'), 'app OperitTerminalManager must not be the kernel');
  assert.ok(read(externalPackFile).includes('PACKAGE_NAME'), 'external package manager purpose');

  const checks = {
    adopted: adopted.length,
    section31: adopted.filter(item => item.section === '3.1').length,
    section32: adopted.filter(item => item.section === '3.2').length,
    rejected: rejectedRows.length,
    evidence: adopted.reduce((total, item) => total + item.evidence.length, 0),
    callChainSteps: adopted.reduce((total, item) => total + item.callChain.length, 0),
    directPort: adopted.filter(item => item.method === 'direct-port').length,
    adapter: adopted.filter(item => item.method === 'adapter').length,
    reimplement: adopted.filter(item => item.method === 'reimplement').length,
    operitPackages: moduleRows.length + terminalRows.length,
    terminalAdopted: terminalRows.filter(item => item.decision === 'adopted').length,
    terminalPartial: terminalRows.filter(item => item.decision === 'partial').length,
    terminalReplaced: terminalRows.filter(item => item.decision === 'replaced').length,
    terminalRejected: terminalRows.filter(item => item.decision === 'rejected').length,
    appPackages: moduleRows.length,
    fileAdoptions: fileRows.length,
    terminalPackages: terminalRows.length,
    packagesAdopted: moduleRows.filter(item => item.decision === 'adopted').length,
    packagesPartial: moduleRows.filter(item => item.decision === 'partial').length,
    packagesReplaced: moduleRows.filter(item => item.decision === 'replaced').length,
    packagesRejected: moduleRows.filter(item => item.decision === 'rejected').length,
    modules: inventory.length,
    brandPages: brandIds.size,
    brandPagesMapped: brandIds.size - pageCoverage.length,
    domains: domainIds.size,
    domainsOwned: ownedDomains.size,
    domainsUnowned: missingDomains.length,
    kernelModule: 'terminal',
    rootfsCodename: rootfs.codename,
    rootfsConsistent: rootfs.consistent,
    externalPackManager: externalPackFile,
  };
  const self = selfChecks();
  assert.equal(self.rejectsFakeSymbol, true);
  assert.equal(self.rejectsWrongLine, true);
  assert.equal(self.rejectsUnknownPackage, true);

  return {
    formatVersion: 1,
    unit,
    delivery: { adoption: unit, assets: 'S0.14', platform: 'S0.15' },
    evidence: {
      plan: { file: planFile, unitLine: 595, sections },
      sourceLock: {
        file: lockFile,
        desktopCommit: desktop.commit,
        desktopTree: desktop.tree,
        operitCommit: operit.commit,
        operitTree: operit.tree,
        terminalCommit: terminal.commit,
        terminalTree: terminal.tree,
      },
      brandPages: { file: brandFile, count: brand.operitPages.length },
      domains: { file: domainsFile, count: domains.plugins.length },
      classification: { file: classificationFile, tables: classification.registry.length },
    },
    desktopSeams: desktopEvidence,
    workbenchSeam: workbenchEvidence,
    brandPageEvidence: brandEvidence,
    domainCoverage: {
      file: domainsFile,
      total: domainIds.size,
      owned: ownedDomains.size,
      unowned: missingDomains.length,
      unownedIds: missingDomains,
      rule: 'adoption units own only domains with a direct Operit seam; the remaining desktop domains stay outside the Operit adoption catalog',
    },
    adopted,
    rejected: rejectedRows,
    modules: moduleRows,
    terminalModules: terminalRows,
    fileAdoptions: fileRows,
    inventory,
    rootfs,
    checks: { ...checks, selfChecks: self, operitCommit: operit.commit, terminalCommit: terminal.commit },
  };
}

export async function run(mode) {
  const started = performance.now();
  const snapshot = collect();
  log('adoption.coverage', snapshot.checks);
  log('adoption.selfChecks', snapshot.checks.selfChecks);
  artifact(file, snapshot, mode);
  log('adoption.complete', { unit, mode, elapsedMs: Math.round(performance.now() - started), ...snapshot.checks });
}

main(import.meta.url, run);
