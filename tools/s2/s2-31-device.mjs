// Runs the diagnostics surface against the sealed device-only app, never selecting a share recipient.
import assert from 'node:assert/strict';
import { isolatedArguments, withIsolatedDevice } from './isolated-device-session.mjs';

const suite = 'com.deepstudent.app.diagnostics';
const cases = [
  ['live-route', 'S231DiagnosticsDeviceTest', 'actualActivityShowsRealOperationLoggerEntryAndSurvivesRecreate'],
  ['chooser-cancel', 'S231DiagnosticsDeviceTest', 'tappingShareOpensSystemChooserAndBackCancelsWithoutSending'],
  ['read-only-file', 'S231DiagnosticsDeviceTest', 'fileProviderSharesOnlyProjectedTextWithReadGrant'],
  ['preserve-workspace', 'S231HostStateDeviceTest', 'openingDiagnosticsDoesNotDisposeUnderlyingWorkspace'],
  ['live-auth-redaction', 'S231RealFailureDeviceTest', 'rejectedRealWebDavAuthenticationIsCorrelatedAndNotDisclosedByShare'],
];

const receipt = await withIsolatedDevice(isolatedArguments(process.argv.slice(2)), async device => {
  for (const [label, classname, method] of cases) await device.instrument(label, `${suite}.${classname}#${method}`);
  return { chooserOpenedAndCancelled: true, sharedToThirdParty: false, fileReadGrant: 'read-only',
    credentialCanariesAbsent: true, realWebDavRejectedWithoutSavingCredentials: true };
});
assert.equal(receipt.cases.length, cases.length);
console.log(JSON.stringify({ event: 's2.31.physical', model: receipt.model, passed: receipt.cases.length,
  skipped: 0, apkSetSha256: receipt.apkSetSha256 }));
