import assert from 'node:assert/strict';
import { test } from 'node:test';
import { countExecutedTests } from './check-device-results.mjs';
test('successful device tests count both empty and nested test cases', () => {
  assert.equal(countExecutedTests('<testsuite><testcase name="a"/><testcase name="b"><system-out>ok</system-out></testcase></testsuite>'), 2);
});
test('empty and fully skipped suites cannot turn device CI green', () => {
  for (const xml of ['', '<testsuite tests="0"/>', '<testsuite><testcase name="a"><skipped/></testcase></testsuite>', '<testcase><system-out/><skipped/></testcase>']) {
    assert.throws(() => countExecutedTests(xml), /no executed/u);
  }
});
test('device result failures and errors fail the evidence gate', () => {
  for (const element of ['failure', 'error']) assert.throws(() => countExecutedTests(`<testcase><${element}/></testcase>`), /failing/u);
});
