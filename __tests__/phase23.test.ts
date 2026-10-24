import type { GuidancePayload, RuleId } from '../modules/guided-capture';
import { VoicePolicy, stripArrows } from '../src/guidance/voice';
import { __forTests, RULE_IDS, ruleText } from '../src/i18n/strings';
import { captureReducer, coverageVisible, initialCaptureState } from '../src/state/captureState';

const g = (ruleId: RuleId | null, direction: GuidancePayload['direction'] = null): GuidancePayload => ({
  ruleId, severity: 'INFO', tier: 6, sessionState: 'SCANNING', canStop: false, escalated: false, elapsedMs: 0,
  direction, coverage: { method: 'VOXEL_GRID', surfacesSeenFromTwoPlusAngles: 0.5, observedVoxelCount: 100, coveredVoxelCount: 50 },
});

describe('directional guidance text', () => {
  it('gives every arrow of C1, C2 and C4 its own sentence in both languages', () => {
    for (const [id, dirs] of [['C1', ['LEFT', 'RIGHT', 'BEHIND']], ['C2', ['UP', 'DOWN']], ['C4', ['LEFT', 'RIGHT', 'BEHIND']]] as const) {
      for (const d of dirs) {
        expect(__forTests.directionalEn[id]?.[d]).toBeTruthy();
        expect(__forTests.directionalSi[id]?.[d]).toBeTruthy();
      }
    }
    expect(ruleText('en', 'C1', 'LEFT').short).toMatch(/left/i);
    expect(ruleText('en', 'C2', 'UP').short).toMatch(/up/i);
    expect(ruleText('en', 'C1', null).short).toBe(ruleText('en', 'C1').short);
  });

  it('covers all 27 rule identifiers', () => {
    expect(RULE_IDS).toHaveLength(27);
    expect(new Set(RULE_IDS).size).toBe(27);
  });
});

describe('voice policy', () => {
  it('speaks only when the rule changes and not again within the gap', () => {
    const p = new VoicePolicy(8000);
    expect(p.next(g('G4'), 'en', 0)?.text).toMatch(/Walk around/);
    expect(p.next(g('G4'), 'en', 100)).toBeNull(); // same rule still shown
    expect(p.next(g(null), 'en', 200)).toBeNull(); // nothing to say
    expect(p.next(g('G4'), 'en', 3000)).toBeNull(); // too soon to repeat
    expect(p.next(g('L1'), 'en', 3100)).not.toBeNull();
    expect(p.next(g('G4'), 'en', 9000)).not.toBeNull();
  });

  it('uses Sinhala with an English fallback and strips arrows', () => {
    const u = new VoicePolicy().next(g('C1', 'RIGHT'), 'si', 0)!;
    expect(u.languageTag).toBe('si-LK');
    expect(u.fallbackEnglish).toMatch(/Turn right/);
    expect(u.text).not.toMatch(/[←→↑↓↻]/);
    expect(stripArrows('Turn left ← — you have not')).toBe('Turn left. you have not');
  });
});

describe('honest coverage label', () => {
  it('is hidden for POSE_TRAIL and until enough surfaces are measured', () => {
    let s = captureReducer(initialCaptureState, { type: 'start' });
    expect(coverageVisible(s)).toBe(false);
    s = { ...s, coverageMethod: 'POINTCLOUD_ANGULAR', coverageObserved: 10 };
    expect(coverageVisible(s)).toBe(false);
    s = { ...s, coverageObserved: 80 };
    expect(coverageVisible(s)).toBe(true);
  });
});
