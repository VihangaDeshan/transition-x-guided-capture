import { __forTests, RULE_IDS, failureHint, ruleText, uiText } from '../src/i18n/strings';

const PHASE1: string[] = ['T1', 'T2', 'T3', 'T4', 'T5', 'T6', 'G1', 'G2', 'G3', 'G4', 'L1', 'L2', 'L3', 'S1', 'C6'];

describe('guidance strings', () => {
  it('has English and Sinhala text for every rule identifier', () => {
    for (const id of RULE_IDS) {
      expect(__forTests.rulesEn[id]?.short).toBeTruthy();
      expect(__forTests.rulesSi[id]?.short).toBeTruthy();
    }
  });

  it('gives the dominant failure (G4) and tracking rules a fuller escalated explanation', () => {
    for (const id of ['G4', 'T1', 'T2', 'G1', 'G3', 'L3'] as const) {
      expect(ruleText('en', id).detail).toBeTruthy();
      expect(ruleText('si', id).detail).toBeTruthy();
    }
  });

  it('never uses jargon in Phase 1 messages', () => {
    for (const id of PHASE1) {
      const t = ruleText('en', id as never).short.toLowerCase();
      expect(t).not.toMatch(/parallax|baseline|laplacian|feature/);
    }
  });

  it('has the same UI keys in both languages', () => {
    expect(Object.keys(__forTests.ui.si).sort()).toEqual(Object.keys(__forTests.ui.en).sort());
    expect(uiText('si', 'tips')).toHaveLength(3);
  });

  it('explains tracking-failure reasons', () => {
    expect(failureHint('en', 'EXCESSIVE_MOTION')).toMatch(/fast/);
    expect(failureHint('si', 'NONE')).toBe('');
  });
});
