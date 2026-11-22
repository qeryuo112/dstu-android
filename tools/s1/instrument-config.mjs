import assert from 'node:assert/strict';

export function instrumentConfig([module = 'app', target = 'com.deepstudent.app.test', minimumText = '1', testClass]) {
  assert.match(module, /^(?:app|(?:core|engines|platform)\/[a-z][a-z0-9_-]*|(?:features|services)\/[a-z][a-z0-9_-]*\/[a-z][a-z0-9_-]*)$/u);
  assert.match(target, /^com\.deepstudent\.[a-z.]+$/u);
  if (testClass) assert.match(testClass, /^com\.deepstudent\.[A-Za-z0-9_.$,#]+$/u);
  const minimum = Number(minimumText);
  assert.ok(Number.isInteger(minimum) && minimum > 0);
  const prefix = module.split('/').at(-1);
  return { module, target, minimum, testClass,
    testApk: `${module}/build/outputs/apk/androidTest/debug/${prefix}-debug-androidTest.apk` };
}
