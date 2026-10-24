import type { GuidancePayload, StatusPayload } from '../modules/guided-capture';
import { captureReducer, initialCaptureState, nearSessionLimit } from '../src/state/captureState';

const guidance = (over: Partial<GuidancePayload> = {}): GuidancePayload => ({
  ruleId: 'G4', severity: 'CRITICAL', tier: 1, sessionState: 'SCANNING', canStop: false, escalated: false,
  elapsedMs: 5000, direction: null, coverage: { method: 'POSE_TRAIL', surfacesSeenFromTwoPlusAngles: 0, observedVoxelCount: 0, coveredVoxelCount: 0 },
  ...over,
});

const status = (over: Partial<StatusPayload> = {}): StatusPayload => ({
  sessionState: 'SCANNING', trackingStatus: 'TRACKING', trackingFailure: 'NONE', elapsedMs: 6000, canStop: false,
  keyframeCount: 3, pathLengthM: 0.5, maxDistanceFromStartM: 0.4, thermalStatus: 0, position: [0.2, 0.1],
  trailAppend: [[0.1, 0]], coverageMethod: 'VOXEL_GRID', coverageFraction: 0.42, coverageObserved: 300,
  coverageCovered: 126, distanceToSurfaceM: 1.8, maxSessionMs: 600000, warnBeforeEndMs: 60000, ...over,
});

describe('capture reducer', () => {
  it('ignores guidance while only previewing', () => {
    const s = captureReducer(initialCaptureState, { type: 'guidance', payload: guidance() });
    expect(s.guidance).toBeNull();
  });

  it('runs start -> started -> guidance -> status -> stop', () => {
    let s = captureReducer(initialCaptureState, { type: 'start' });
    expect(s.phase).toBe('starting');
    s = captureReducer(s, { type: 'started' });
    s = captureReducer(s, { type: 'guidance', payload: guidance() });
    expect(s.guidance?.ruleId).toBe('G4');
    s = captureReducer(s, { type: 'status', payload: status() });
    s = captureReducer(s, { type: 'status', payload: status({ trailAppend: [[0.3, 0.1], [0.5, 0.2]] }) });
    expect(s.trail).toHaveLength(3);
    expect(s.position).toEqual([0.2, 0.1]);
    expect(s.elapsedMs).toBe(6000);
    expect(s.coverageFraction).toBeCloseTo(0.42);
    expect(s.coverageMethod).toBe('VOXEL_GRID');
    s = captureReducer(s, { type: 'stop' });
    expect(s.phase).toBe('stopping');
  });

  it('a failed start returns to preview with the error shown', () => {
    let s = captureReducer(initialCaptureState, { type: 'start' });
    s = captureReducer(s, { type: 'error', message: 'ERR_STORAGE: full' });
    expect(s.phase).toBe('preview');
    expect(s.error).toMatch(/STORAGE/);
  });

  it('warns in the final minute before the session limit', () => {
    let s = captureReducer(captureReducer(initialCaptureState, { type: 'start' }), { type: 'started' });
    s = captureReducer(s, { type: 'status', payload: status({ elapsedMs: 530_000 }) });
    expect(nearSessionLimit(s)).toBe(false);
    s = captureReducer(s, { type: 'status', payload: status({ elapsedMs: 545_000 }) });
    expect(nearSessionLimit(s)).toBe(true);
  });
});
