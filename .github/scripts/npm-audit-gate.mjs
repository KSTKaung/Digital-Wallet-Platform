// npm audit gate: fails on any high or critical advisory that is not an accepted risk.
//
// Usage (from the npm project directory): node <this file> <allowlist.json>
//
// The allowlist holds only risks the owner accepted in documentations/06 (MVP-ADR-15). Every
// entry names the advisory id, the package, the reason and the doc reference. A high or critical
// advisory that is not listed fails the build, and so does an audit that cannot be read.
// Listed advisories that npm no longer reports are printed as warnings so the entry gets removed.
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const BLOCKING = new Set(['high', 'critical']);

function fail(message) {
  console.error(`::error::${message}`);
  process.exit(1);
}

const allowlistPath = process.argv[2];
if (!allowlistPath) fail('usage: node npm-audit-gate.mjs <allowlist.json>');

let allowlist;
try {
  allowlist = JSON.parse(readFileSync(allowlistPath, 'utf8'));
} catch (e) {
  fail(`cannot read the allowlist ${allowlistPath}: ${e.message}`);
}
if (!Array.isArray(allowlist.accepted)) fail('allowlist: "accepted" must be an array');
for (const entry of allowlist.accepted) {
  for (const field of ['id', 'package', 'reason', 'reference']) {
    if (typeof entry[field] !== 'string' || entry[field].trim() === '') {
      fail(`allowlist entry ${JSON.stringify(entry)} needs a non-empty "${field}"`);
    }
  }
}
const accepted = new Map(allowlist.accepted.map((entry) => [entry.id, entry]));

// npm audit exits non-zero when it finds anything; the gate decides from the JSON instead.
// On Windows npm is a .cmd file, which needs a shell; the command is a fixed string.
const options = { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 };
const audit =
  process.platform === 'win32'
    ? spawnSync('npm audit --json', { ...options, shell: true })
    : spawnSync('npm', ['audit', '--json'], options);
let report;
try {
  report = JSON.parse(audit.stdout);
} catch {
  fail(`npm audit did not return JSON (exit ${audit.status}): ${audit.stderr || audit.stdout}`);
}
if (report.error || typeof report.vulnerabilities !== 'object' || report.vulnerabilities === null) {
  fail(`npm audit failed: ${JSON.stringify(report.error ?? report)}`);
}

// Advisories sit on the vulnerable package as objects in "via"; dependents only name packages.
const advisories = new Map();
for (const vulnerability of Object.values(report.vulnerabilities)) {
  for (const via of vulnerability.via ?? []) {
    if (typeof via !== 'object' || via === null) continue;
    const id = /GHSA-[0-9a-z]{4}-[0-9a-z]{4}-[0-9a-z]{4}/i.exec(via.url ?? '')?.[0] ?? String(via.source);
    advisories.set(id, { id, package: via.name, severity: via.severity, title: via.title, url: via.url });
  }
}

const blocking = [];
const allowed = [];
for (const advisory of advisories.values()) {
  if (!BLOCKING.has(advisory.severity)) continue;
  (accepted.has(advisory.id) ? allowed : blocking).push(advisory);
}

for (const advisory of allowed) {
  const entry = accepted.get(advisory.id);
  console.log(
    `accepted risk: ${advisory.id} ${advisory.severity} ${advisory.package} (${entry.reference})`,
  );
}
for (const entry of accepted.values()) {
  if (!advisories.has(entry.id)) {
    console.log(`::warning::${entry.id} (${entry.package}) is no longer reported; remove it from the allowlist and doc 06`);
  }
}
if (blocking.length > 0) {
  for (const advisory of blocking) {
    console.error(
      `::error::${advisory.severity} ${advisory.id} in ${advisory.package}: ${advisory.title} ${advisory.url ?? ''}`,
    );
  }
  fail(`${blocking.length} high or critical advisory(ies) not accepted in doc 06`);
}
console.log(`npm audit gate passed: ${advisories.size} advisory(ies), none high or critical outside the allowlist`);
