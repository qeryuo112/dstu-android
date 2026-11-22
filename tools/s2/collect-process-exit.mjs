#!/usr/bin/env node
// Read-only, best-effort host evidence. Never print raw dumpsys/logcat output or traces.
// Usage: node tools/s2/collect-process-exit.mjs --adb ABSOLUTE_PATH --serial SERIAL
//   --package PACKAGE --since-ms DEVICE_UNIX_MILLISECONDS [--since-tz +HHMM] [--pid PID]
// Capture both start arguments together, before the operation: adb shell date +%s%3N,%z
// Without a verified unchanged start timezone, local-time exit-info is not correlated.
import { spawnSync } from 'node:child_process';
import { isAbsolute } from 'node:path';

const MAX_WINDOW_MS = 10 * 60 * 1000;
const COMMAND_TIMEOUT_MS = 3500;
const MAX_BUFFER = 1024 * 1024;
const reasonNames = ['UNKNOWN', 'EXIT_SELF', 'SIGNALED', 'LOW_MEMORY', 'CRASH', 'CRASH_NATIVE', 'ANR',
  'INITIALIZATION_FAILURE', 'PERMISSION_CHANGE', 'EXCESSIVE_RESOURCE_USAGE', 'USER_REQUESTED', 'USER_STOPPED',
  'DEPENDENCY_DIED', 'OTHER', 'FREEZER', 'PACKAGE_STATE_CHANGE', 'PACKAGE_UPDATED'];
const subreasonNames = { 6: 'MEMORY_PRESSURE', 21: 'FORCE_STOP', 22: 'REMOVE_TASK', 23: 'STOP_APP',
  24: 'KILL_BACKGROUND', 25: 'PACKAGE_UPDATE' };
const emit = (type, values) => console.log(JSON.stringify({ type, ...values }));
const safeInt = text => /^\d{1,10}$/.test(text ?? '') ? Number(text) : null;
const escapeRe = text => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

function argsOf(args) {
  const out = {};
  const allowed = new Set(['--adb', '--serial', '--package', '--since-ms', '--since-tz', '--pid']);
  for (let i = 0; i < args.length; i += 2) {
    if (!allowed.has(args[i]) || i + 1 >= args.length || Object.hasOwn(out, args[i])) return null;
    out[args[i]] = args[i + 1];
  }
  if (!out['--adb'] || !isAbsolute(out['--adb']) || !/^[A-Za-z0-9_.:-]{1,128}$/.test(out['--serial'] ?? '') ||
      !/^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+$/.test(out['--package'] ?? '') ||
      !/^(?:0|[1-9]\d{12})$/.test(out['--since-ms'] ?? '') ||
      (out['--pid'] !== undefined && (!/^[1-9]\d{0,9}$/.test(out['--pid']) || safeInt(out['--pid']) === null)) ||
      (out['--since-tz'] !== undefined && out['--since-tz'] !== 'unknown' && offsetMinutes(out['--since-tz']) === null)) return null;
  return { adb: out['--adb'], serial: out['--serial'], pkg: out['--package'], since: Number(out['--since-ms']),
    offset: out['--since-tz'] ?? 'unknown', pid: out['--pid'] === undefined ? null : Number(out['--pid']) };
}

function offsetMinutes(value) {
  const m = /^([+-])(\d\d)(\d\d)$/.exec(value ?? '');
  if (!m || Number(m[2]) > 14 || Number(m[3]) > 59 || (Number(m[2]) === 14 && Number(m[3]) !== 0)) return null;
  return (m[1] === '-' ? -1 : 1) * (Number(m[2]) * 60 + Number(m[3]));
}

function run(adb, args) {
  const result = spawnSync(adb, args, { encoding: 'utf8', timeout: COMMAND_TIMEOUT_MS,
    killSignal: 'SIGKILL', maxBuffer: MAX_BUFFER, windowsHide: true });
  const combined = `${result.stdout ?? ''}\n${result.stderr ?? ''}`;
  let status = 'ok';
  if (result.error?.code === 'ETIMEDOUT') status = 'timeout';
  else if (result.error?.code === 'ENOBUFS') status = 'output_limit';
  else if (/permission denial|permission denied|securityexception|not allowed to/i.test(combined)) status = 'permission_denied';
  else if (result.error || result.status !== 0) status = 'command_failed';
  return { status, text: result.stdout ?? '' };
}

// Parse Android's yyyy-MM-dd HH:mm:ss.SSS using the device's verified UTC offset.
function localTimeMs(value, offset) {
  const m = /^(\d{4})-(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3})$/.exec(value ?? '');
  if (!m || offset === null) return null;
  const n = m.slice(1).map(Number);
  const utc = Date.UTC(n[0], n[1] - 1, n[2], n[3], n[4], n[5], n[6]);
  const d = new Date(utc);
  if (d.getUTCFullYear() !== n[0] || d.getUTCMonth() !== n[1] - 1 || d.getUTCDate() !== n[2] ||
      d.getUTCHours() !== n[3] || d.getUTCMinutes() !== n[4] || d.getUTCSeconds() !== n[5]) return null;
  return utc - offset * 60_000;
}

function inWindow(t, window) { return t !== null && t >= window.since && t <= window.until; }
function targetProcess(name, pkg) {
  return name === pkg || (name.startsWith(`${pkg}:`) && /^[A-Za-z0-9_.-]+$/.test(name.slice(pkg.length + 1)));
}
function mentionsPackage(text, pkg) {
  return new RegExp(`(?:^|[^A-Za-z0-9_.])${escapeRe(pkg)}(?=$|[^A-Za-z0-9_.])`).test(text);
}

// Only numerical caller fields and fixed classifications leave this function, never the description itself.
function causeMetadata(text) {
  const out = {};
  const oem = /\bforceStopPackageByUid\s+(\d{1,10})\s*pid\s*[=:]?\s*(\d{1,10})\b/i.exec(text);
  const pid = /\b(?:calling|caller)[ _-]*pid\s*[=: ]\s*(\d{1,10})\b/i.exec(text) ?? /\b(?:from|by)\s+pid\s*[=: ]\s*(\d{1,10})\b/i.exec(text);
  const uid = /\b(?:calling|caller)[ _-]*uid\s*[=: ]\s*(\d{1,10})\b/i.exec(text);
  if (/\b(?:finished\s+inst(?:rumentation)?|start\s+instr(?:umentation)?)\b/i.test(text)) {
    out.descriptionClass = 'instrumentation_lifecycle';
  } else if (/\bforceStopPackageByUid\b/i.test(text)) out.descriptionClass = 'forceStopPackageByUid';
  else if (/\b(?:force[ -]?stop(?:ping)?|stop .* due to)\b/i.test(text)) out.descriptionClass = 'force_stop_related';
  else if (/\b(?:low[ -]?memory|memory pressure|lmkd)\b/i.test(text)) out.descriptionClass = 'memory_related';
  else out.descriptionClass = 'other_or_unavailable';
  if (oem) {
    // OEM string gives a UID/PID pair; its exact semantics are not established by AOSP.
    out.reportedCallerUid = Number(oem[1]);
    out.reportedCallerPid = Number(oem[2]);
    out.callerAttribution = 'oem_text_unverified';
  } else {
    if (pid) out.reportedCallerPid = Number(pid[1]);
    if (uid) out.reportedCallerUid = Number(uid[1]);
    if (pid || uid) out.callerAttribution = 'text_reported_unverified';
  }
  return out;
}

function exitEntries(text, config, window, offset) {
  const entries = [];
  let section = null, record = null, total = 0, malformed = 0;
  function flush() {
    if (!record) return;
    total++;
    const body = record.lines.join(' ');
    // Never treat tokens embedded in an arbitrary description/trace as exit fields.
    const metadata = body.split(/\bdescription=/, 1)[0];
    const time = /\btimestamp=(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\b/.exec(metadata)?.[1];
    const pid = safeInt(/\bpid=(\d+)\b/.exec(metadata)?.[1]);
    const process = /\bprocess=([^\s]+)/.exec(metadata)?.[1];
    const reason = safeInt(/\breason=(\d+)\b/.exec(metadata)?.[1]);
    const subreason = safeInt(/\bsubreason=(\d+)\b/.exec(metadata)?.[1]);
    const statusMatch = /\bstatus=(-?\d+)\b/.exec(metadata);
    const timestampMs = localTimeMs(time, offset);
    if (timestampMs === null || pid === null || !process || reason === null || !statusMatch) { malformed++; return; }
    if ((record.section !== null && record.section !== config.pkg) || !targetProcess(process, config.pkg) ||
        !inWindow(timestampMs, window) || (config.pid !== null && pid !== config.pid)) return;
    const uid = name => safeInt(new RegExp(`\\b${name}=(\\d+)\\b`).exec(metadata)?.[1]);
    const description = /\bdescription=(.*?)(?:\s+state=|\s+trace=|$)/.exec(body)?.[1] ?? '';
    entries.push({ timestampMs, pid, process, realUid: uid('realUid'), packageUid: uid('packageUid'),
      definingUid: uid('definingUid'), userId: uid('user'), reasonCode: reason,
      reasonName: reasonNames[reason] ?? 'UNRECOGNIZED', subreasonCode: subreason,
      subreasonName: subreasonNames[subreason] ?? 'OTHER_OR_UNRECOGNIZED', status: Number(statusMatch[1]),
      ...causeMetadata(description) });
  }
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    const sectionMatch = /^package:\s*(\S+)\s*$/.exec(line);
    if (sectionMatch) { flush(); record = null; section = sectionMatch[1]; }
    else if (/^ApplicationExitInfo(?:\s+#\d+:|\()/.test(line)) {
      flush(); record = { section, lines: [line] };
    } else if (record) record.lines.push(line);
  }
  flush();
  return { entries, total, malformed };
}

function logLine(raw, window, offset) {
  // -v epoch is preferred; accept OEM/default threadtime only with a verified timezone.
  const epoch = /^\s*(\d{10,13})\.(\d{3,9})\s+\d+\s+\d+\s+[VDIWEF]\s+([^\s:]+)\s*:\s*(.*)$/.exec(raw);
  if (epoch) {
    const seconds = Number(epoch[1]);
    const time = seconds < 10_000_000_000 ? seconds * 1000 + Number(epoch[2].slice(0, 3)) : null;
    return { timestampMs: time, tag: epoch[3], message: epoch[4] };
  }
  const local = /^\s*(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+\d+\s+\d+\s+[VDIWEF]\s+([^\s:]+)\s*:\s*(.*)$/.exec(raw);
  if (!local || offset === null) return null;
  const years = new Set([window.since, window.until].map(t => new Date(t + offset * 60_000).getUTCFullYear()));
  const candidates = [...years].map(year => localTimeMs(`${year}-${local[1]}`, offset)).filter(t => inWindow(t, window));
  return candidates.length === 1 ? { timestampMs: candidates[0], tag: local[2], message: local[3] } : null;
}

function killEntries(text, config, window, offset) {
  const result = [];
  for (const line of text.split(/\r?\n/)) {
    const parsed = logLine(line, window, offset);
    if (!parsed || parsed.tag !== 'am_kill' || !inWindow(parsed.timestampMs, window)) continue;
    const m = /^\[(\d+),\s*(\d+),\s*([^,\s]+),\s*(-?\d+),\s*(.*)\]$/.exec(parsed.message.trim());
    if (!m || !targetProcess(m[3], config.pkg)) continue;
    const pid = safeInt(m[2]);
    if (pid === null || (config.pid !== null && config.pid !== pid)) continue;
    result.push({ timestampMs: parsed.timestampMs, userId: safeInt(m[1]), pid, process: m[3], oomAdj: Number(m[4]),
      ...causeMetadata(m[5]) });
  }
  return result;
}

function forceStopEntries(text, config, window, offset) {
  const result = [];
  for (const line of text.split(/\r?\n/)) {
    const parsed = logLine(line, window, offset);
    if (!parsed || parsed.tag !== 'ActivityManager' || !inWindow(parsed.timestampMs, window) ||
        !/\bforceStopPackageByUid\b/i.test(parsed.message) || !mentionsPackage(parsed.message, config.pkg)) continue;
    // A force-stop line may not name the target PID. Do not pretend a PID filter matched it.
    const target = /\b(?:targetPid|processPid)\s*[=: ]\s*(\d{1,10})\b/i.exec(parsed.message);
    const targetPid = target ? Number(target[1]) : null;
    if (config.pid !== null && targetPid !== config.pid) continue;
    result.push({ timestampMs: parsed.timestampMs, targetPid, ...causeMetadata(parsed.message) });
  }
  return result;
}

function main() {
  const config = argsOf(process.argv.slice(2));
  if (!config) { emit('collector', { status: 'invalid_arguments' }); return 2; }
  emit('collector', { status: 'started', package: config.pkg, sinceMs: config.since, requestedPid: config.pid,
    windowUnit: 'unix_ms', maxWindowMs: MAX_WINDOW_MS });
  const devices = run(config.adb, ['devices']);
  if (devices.status !== 'ok') { emit('collector', { status: 'device_list_' + devices.status }); return 1; }
  const lines = devices.text.replace(/\r/g, '').trim().split('\n');
  if (lines[0] !== 'List of devices attached' || lines.slice(1).filter(Boolean).length !== 1 ||
      lines.slice(1).filter(Boolean)[0]?.trim().split(/\s+/).join(' ') !== `${config.serial} device`) {
    emit('collector', { status: 'single_device_required' }); return 1;
  }
  const adb = args => run(config.adb, ['-s', config.serial, ...args]);
  const clock = adb(['shell', 'date', '+%s%3N,%z']);
  const clockMatch = /^(\d{13}),([+-]\d{4})\s*$/.exec(clock.text.replace(/\r/g, ''));
  const until = clock.status === 'ok' && clockMatch ? Number(clockMatch[1]) : null;
  const validWindow = until !== null && config.since > 0 && until >= config.since && until - config.since <= MAX_WINDOW_MS;
  const startOffset = offsetMinutes(config.offset);
  const endOffset = clockMatch ? offsetMinutes(clockMatch[2]) : null;
  const offset = validWindow && startOffset !== null && startOffset === endOffset ? startOffset : null;
  const window = { since: config.since, until };
  emit('window', { status: !validWindow ? 'unavailable_or_invalid' : offset === null ? 'timezone_unverified' : 'verified',
    sinceMs: config.since, untilMs: until, unit: 'unix_ms', clockStatus: clock.status });

  // Always request the package-scoped dump even when the clock failed; never publish unfilterable records.
  const dump = adb(['shell', 'dumpsys', 'activity', 'exit-info', config.pkg]);
  let exits = [];
  if (dump.status !== 'ok') emit('exitInfoSummary', { status: dump.status, count: 0 });
  else if (!validWindow) emit('exitInfoSummary', { status: 'window_unavailable', count: 0 });
  else if (offset === null) emit('exitInfoSummary', { status: 'timezone_unverified', count: 0 });
  else {
    const parsed = exitEntries(dump.text, config, window, offset);
    exits = parsed.entries;
    for (const entry of exits) emit('exitInfo', entry);
    const recognized = /ACTIVITY MANAGER PROCESS EXIT INFO|ApplicationExitInfo|\bNo (?:historical |process )*exit (?:info(?:rmation)?|history)/i.test(dump.text);
    const status = exits.length ? 'matched' : parsed.total ? (parsed.malformed === parsed.total ? 'unrecognized_format' : 'no_match') :
      recognized ? 'no_history' : 'unrecognized_format';
    emit('exitInfoSummary', { status, count: exits.length, malformedRecords: parsed.malformed });
  }
  if (!validWindow) {
    emit('amKillSummary', { status: 'window_unavailable', count: 0 });
    emit('forceStopSummary', { status: 'window_unavailable', count: 0 });
  } else {
    const sinceLogcat = `${Math.floor(config.since / 1000)}.${String(config.since % 1000).padStart(3, '0')}`;
    const kills = adb(['logcat', '-b', 'events', '-d', '-v', 'epoch', '-T', sinceLogcat, '-s', 'am_kill:I', '*:S']);
    if (kills.status !== 'ok') emit('amKillSummary', { status: kills.status, count: 0 });
    else {
      const entries = killEntries(kills.text, config, window, offset);
      for (const entry of entries) {
        const related = exits.filter(exit => exit.pid === entry.pid && exit.process === entry.process &&
          Math.abs(exit.timestampMs - entry.timestampMs) <= 10_000);
        emit('amKill', { ...entry, exitCorrelation: related.length ? 'same_pid_process_within_10s_not_causation' : 'unconfirmed',
          matchingExitTimeMs: related.length === 1 ? related[0].timestampMs : null });
      }
      emit('amKillSummary', { status: entries.length ? 'matched' : 'no_match', count: entries.length });
    }
    const forces = adb(['logcat', '-b', 'system', '-d', '-v', 'epoch', '-T', sinceLogcat, '-s', 'ActivityManager:I', '*:S']);
    if (forces.status !== 'ok') emit('forceStopSummary', { status: forces.status, count: 0 });
    else {
      const entries = forceStopEntries(forces.text, config, window, offset);
      for (const entry of entries) emit('forceStop', { ...entry, exitCorrelation: 'unconfirmed' });
      emit('forceStopSummary', { status: entries.length ? 'matched' : 'no_match', count: entries.length });
    }
  }
  emit('collector', { status: 'finished' });
  return 0;
}

try { process.exitCode = main(); }
catch { emit('collector', { status: 'internal_error' }); process.exitCode = 1; }
