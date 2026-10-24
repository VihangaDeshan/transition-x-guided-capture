// Cross-language checks: the TypeScript side must agree with the Kotlin engine and the config.
import * as fs from 'fs';
import * as path from 'path';

import { APP_VERSION, THRESHOLDS } from '../src/config';
import { RULE_TIER, severityForRule } from '../src/guidance/rules';
import { RULE_IDS } from '../src/i18n/strings';

const root = path.join(__dirname, '..');
const kt = (p: string) => fs.readFileSync(path.join(root, 'modules/guided-capture/android/src/main/java/lk/transitionx/capture', p), 'utf8');

describe('TypeScript <-> Kotlin consistency', () => {
  it('rule tiers match GuidanceRuleId in Contracts.kt', () => {
    const src = kt('core/Contracts.kt');
    const found: Record<string, number> = {};
    for (const m of src.matchAll(/\b([TGLSC]\d)\((\d), (\d)\)/g)) found[m[1]] = Number(m[2]);
    expect(Object.keys(found).sort()).toEqual([...RULE_IDS].sort());
    for (const id of RULE_IDS) expect([id, RULE_TIER[id]]).toEqual([id, found[id]]);
  });

  it('severity follows the tier', () => {
    expect(severityForRule('T1')).toBe('CRITICAL');
    expect(severityForRule('G4')).toBe('CRITICAL');
    expect(severityForRule('L3')).toBe('WARNING');
    expect(severityForRule('C6')).toBe('INFO');
  });

  it('every threshold key in the JSON is read by Thresholds.fromMap and vice versa', () => {
    const src = kt('guidance/Thresholds.kt');
    const keys = new Set([...src.matchAll(/[fil]\("(\w+)"/g)].map((m) => m[1]));
    const json = Object.keys(THRESHOLDS).filter((k) => !k.startsWith('_') && k !== 'version');
    expect(json.sort()).toEqual([...keys].sort());
    expect(typeof THRESHOLDS.version).toBe('string');
  });

  it('app version matches app.json', () => {
    const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
    expect(app.expo.version).toBe(APP_VERSION);
  });

  it('pure Kotlin packages import no platform types (NFR-10)', () => {
    const base = path.join(root, 'modules/guided-capture/android/src/main/java/lk/transitionx/capture');
    for (const pkg of ['core', 'signals', 'guidance', 'replay', 'export']) {
      for (const f of fs.readdirSync(path.join(base, pkg))) {
        const text = fs.readFileSync(path.join(base, pkg, f), 'utf8');
        expect([f, /^import (android|androidx|com\.google\.ar|expo|com\.facebook)\./m.test(text)]).toEqual([f, false]);
      }
    }
  });
});
