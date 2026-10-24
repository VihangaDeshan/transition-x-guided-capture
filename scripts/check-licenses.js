#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// Licence audit (TDD v1.0 section 21): every production dependency must be permissively licensed.
// Walks node_modules for packages reachable from "dependencies" and fails on copyleft/source-available.
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const DENY = /\b(A?GPL|LGPL|SSPL|BUSL|Commons-Clause|CC-BY-NC|EUPL|OSL|RPL|Elastic)\b/i;
const seen = new Map();

function readPkg(dir) {
  try { return JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8')); } catch { return null; }
}

function resolve(name, fromDir) {
  let dir = fromDir;
  while (true) {
    const candidate = path.join(dir, 'node_modules', name);
    if (fs.existsSync(path.join(candidate, 'package.json'))) return candidate;
    const parent = path.dirname(dir);
    if (parent === dir) return null;
    dir = parent;
  }
}

function licenseOf(pkg) {
  if (typeof pkg.license === 'string') return pkg.license;
  if (pkg.license && pkg.license.type) return pkg.license.type;
  if (Array.isArray(pkg.licenses)) return pkg.licenses.map((l) => l.type || l).join(' OR ');
  return 'UNKNOWN';
}

function walk(dir) {
  const pkg = readPkg(dir);
  if (!pkg) return;
  const key = `${pkg.name}@${pkg.version}`;
  if (seen.has(key)) return;
  seen.set(key, licenseOf(pkg));
  for (const dep of Object.keys({ ...(pkg.dependencies || {}), ...(pkg.optionalDependencies || {}) })) {
    const d = resolve(dep, dir);
    if (d) walk(d);
  }
}

const app = readPkg(root);
for (const dep of Object.keys(app.dependencies || {})) {
  const d = resolve(dep, root);
  if (d) walk(d);
}
seen.delete(`${app.name}@${app.version}`);

const bad = [...seen].filter(([, lic]) => {
  // "X OR Y" is fine if any alternative is permissive.
  const alts = lic.replace(/[()]/g, '').split(/\s+OR\s+/i);
  return alts.every((a) => DENY.test(a));
});
const unknown = [...seen].filter(([, lic]) => lic === 'UNKNOWN');
console.log(`Checked ${seen.size} production packages.`);
if (unknown.length) console.log(`No licence field (review manually): ${unknown.map(([k]) => k).join(', ')}`);
if (bad.length) {
  console.error('Non-permissive licences found:');
  for (const [k, l] of bad) console.error(`  ${k}: ${l}`);
  process.exit(1);
}
console.log('All production dependencies are permissively licensed.');
