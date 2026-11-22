const STABLE_SEMVER = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/u;

export const MAJOR_STRIDE = 10_000_000;
export const MINOR_STRIDE = 100_000;
export const MAX_MINOR = 99;
export const MAX_PATCH = 99_999;
export const MAX_VERSION_CODE = 2_100_000_000;
export const VERSION_CODE_OFFSET = 0;

export const INITIAL_VERSION = '0.21.0';

export const OBSERVED_VERSION_NAME = '0.20.0';
export const OBSERVED_VERSION_CODE = 1_114_586;

export const LEGACY_ANCHOR = Object.freeze({
  version: '0.9.55',
  versionCode: 14641,
  publishedVersionCode: 14640,
  buildNumberBase: 14638,
});

export function parseVersion(version) {
  if (typeof version !== 'string') {
    throw new Error(`version must be a string, got ${typeof version}`);
  }
  const match = STABLE_SEMVER.exec(version);
  if (!match) {
    throw new Error(
      `version ${JSON.stringify(version)} must be a stable major.minor.patch semantic version without leading zeros, prerelease or build metadata`,
    );
  }
  const [, majorText, minorText, patchText] = match;
  const major = Number(majorText);
  const minor = Number(minorText);
  const patch = Number(patchText);
  if (!Number.isSafeInteger(major) || !Number.isSafeInteger(minor) || !Number.isSafeInteger(patch)) {
    throw new Error(`version ${version} exceeds the supported integer range`);
  }
  if (minor > MAX_MINOR) {
    throw new Error(`version ${version} minor ${minor} exceeds the supported maximum ${MAX_MINOR}`);
  }
  if (patch > MAX_PATCH) {
    throw new Error(`version ${version} patch ${patch} exceeds the supported maximum ${MAX_PATCH}`);
  }
  return { major, minor, patch };
}

export function versionCode(version) {
  const { major, minor, patch } = parseVersion(version);
  const code = major * MAJOR_STRIDE + minor * MINOR_STRIDE + patch + VERSION_CODE_OFFSET;
  if (!Number.isSafeInteger(code) || code <= 0 || code > MAX_VERSION_CODE) {
    throw new Error(
      `version ${version} maps to ${code}, outside the supported versionCode range 1..${MAX_VERSION_CODE}`,
    );
  }
  return code;
}

export function compareVersions(left, right) {
  const a = parseVersion(left);
  const b = parseVersion(right);
  for (const key of ['major', 'minor', 'patch']) {
    if (a[key] !== b[key]) return a[key] < b[key] ? -1 : 1;
  }
  return 0;
}

export function releaseVersion(version) {
  const code = versionCode(version);
  if (compareVersions(version, INITIAL_VERSION) < 0) {
    throw new Error(
      `version ${version} predates the new release line ${INITIAL_VERSION}; legacy versions are not publishable from the new source`,
    );
  }
  return Object.freeze({ version, versionCode: code });
}

export function isUpgrade(previousVersion, nextVersion) {
  const versionOrder = compareVersions(nextVersion, previousVersion);
  return versionOrder > 0 && versionCode(nextVersion) > versionCode(previousVersion);
}

export function verifyRelease(version, code) {
  let expected;
  try {
    expected = releaseVersion(version).versionCode;
  } catch (error) {
    return { ok: false, expected: null, actual: code, reason: error.message };
  }
  if (!Number.isSafeInteger(code) || code <= 0) {
    return { ok: false, expected, actual: code, reason: 'versionCode must be a positive safe integer' };
  }
  if (code !== expected) {
    return {
      ok: false,
      expected,
      actual: code,
      reason: `versionCode ${code} does not match the single-source mapping for ${version} (expected ${expected})`,
    };
  }
  return { ok: true, expected, actual: code, reason: null };
}
