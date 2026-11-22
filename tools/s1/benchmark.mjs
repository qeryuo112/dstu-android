#!/usr/bin/env node
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluate, loadRecord, observe, readJson, validateContract, validateObservationContract } from './benchmark-lib.mjs';

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const defaultContract = resolve(root, 'config/benchmark-s1-27.json');
function usage() {
  return 'Usage:\n  node tools/s1/benchmark.mjs check-contract [--contract FILE]\n  node tools/s1/benchmark.mjs report [--contract FILE] [--run FILE ...] [--cost FILE] [--out FILE]\n  node tools/s1/benchmark.mjs observe --run FILE [--run FILE --require-two] [--functional] [--out FILE]\nExit: 0 pass/complete, 1 fail, 2 missing/not ready, 3 invalid invocation.';
}
function parse(args) {
  const command = args.shift();
  const values = { command, contract: defaultContract, runs: [], requireTwo: false, functional: false };
  let contractSpecified = false;
  while (args.length) {
    const flag = args.shift();
    if (flag === '--require-two' || flag === '--functional') {
      const key = flag === '--require-two' ? 'requireTwo' : 'functional';
      if (values[key]) throw new Error(usage());
      values[key] = true;
      continue;
    }
    const value = args.shift();
    if (!value || value.startsWith('--')) throw new Error(`Missing argument for ${flag}`);
    if (flag === '--run') values.runs.push(value);
    else if (['--contract', '--cost', '--out'].includes(flag)) {
      values[flag.slice(2)] = value;
      if (flag === '--contract') contractSpecified = true;
    } else throw new Error(`Unknown option ${flag}`);
  }
  if (!['check-contract', 'report', 'observe'].includes(command)) throw new Error(usage());
  if (command === 'check-contract' && (values.runs.length || values.cost || values.out || values.requireTwo || values.functional)) throw new Error(usage());
  if (command === 'report' && (values.requireTwo || values.functional)) throw new Error(usage());
  if (command === 'observe' && (!values.runs.length || values.cost || contractSpecified || (!values.requireTwo && values.runs.length > 1))) throw new Error(usage());
  return values;
}
try {
  const options = parse(process.argv.slice(2));
  const contract = options.command === 'observe' ? validateObservationContract(readJson(options.contract)) : validateContract(readJson(options.contract));
  if (options.command === 'check-contract') {
    console.log(JSON.stringify({ unit: contract.unit, status: contract.status, profiles: contract.devices.map(d => d.id), corpusSha256: contract.corpus.sha256, metrics: contract.metrics.map(m => m.id) }, null, 2));
  } else {
    const result = options.command === 'observe'
      ? observe(contract, options.runs.map(loadRecord), { requireTwo: options.requireTwo, functional: options.functional })
      : evaluate(contract, options.runs.map(loadRecord), options.cost ? loadRecord(options.cost) : null);
    const json = `${JSON.stringify(result, null, 2)}\n`;
    if (options.out) writeFileSync(options.out, json, { flag: 'wx' });
    else process.stdout.write(json);
    process.exitCode = ['pass', 'complete'].includes(result.status) ? 0 : result.status === 'fail' ? 1 : 2;
  }
} catch (error) {
  // Observation inputs and paths may contain device identifiers; never echo them.
  console.error(process.argv[2] === 'observe' ? 's1.27 benchmark: unable to process observation or invalid invocation' : `s1.27 benchmark: ${error.message}`);
  process.exitCode = 3;
}
