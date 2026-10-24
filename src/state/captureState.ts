// SPDX-License-Identifier: Apache-2.0
// Capture screen state as a pure reducer, so event handling is testable without a device.
import type {
  GuidancePayload,
  SessionState,
  SignalPayload,
  StatusPayload,
  TrackingFailure,
} from '../../modules/guided-capture';
import { appendTrail, type Pt } from '../utils/trail';

export type Phase = 'preview' | 'starting' | 'recording' | 'stopping';

export interface CaptureState {
  phase: Phase;
  guidance: GuidancePayload | null;
  sessionState: SessionState;
  trackingFailure: TrackingFailure;
  elapsedMs: number;
  canStop: boolean;
  trail: Pt[];
  position: Pt | null;
  pathLengthM: number;
  keyframeCount: number;
  signal: SignalPayload | null;
  maxSessionMs: number;
  warnBeforeEndMs: number;
  coverageMethod: string;
  coverageFraction: number;
  coverageObserved: number;
  distanceToSurfaceM: number | null;
  error: string | null;
}

export const initialCaptureState: CaptureState = {
  phase: 'preview',
  guidance: null,
  sessionState: 'IDLE',
  trackingFailure: 'NONE',
  elapsedMs: 0,
  canStop: false,
  trail: [],
  position: null,
  pathLengthM: 0,
  keyframeCount: 0,
  signal: null,
  maxSessionMs: 600000,
  warnBeforeEndMs: 60000,
  coverageMethod: 'POSE_TRAIL',
  coverageFraction: 0,
  coverageObserved: 0,
  distanceToSurfaceM: null,
  error: null,
};

export type CaptureAction =
  | { type: 'start' }
  | { type: 'started' }
  | { type: 'stop' }
  | { type: 'reset' }
  | { type: 'guidance'; payload: GuidancePayload }
  | { type: 'status'; payload: StatusPayload }
  | { type: 'signal'; payload: SignalPayload }
  | { type: 'error'; message: string };

export function captureReducer(state: CaptureState, action: CaptureAction): CaptureState {
  switch (action.type) {
    case 'start':
      return { ...initialCaptureState, phase: 'starting' };
    case 'started':
      return { ...state, phase: 'recording', error: null };
    case 'stop':
      return { ...state, phase: 'stopping' };
    case 'reset':
      return { ...initialCaptureState };
    case 'guidance':
      if (state.phase === 'preview') return state;
      return {
        ...state,
        guidance: action.payload,
        sessionState: action.payload.sessionState,
        canStop: action.payload.canStop,
        elapsedMs: Math.max(state.elapsedMs, action.payload.elapsedMs),
      };
    case 'status': {
      if (state.phase === 'preview') return state;
      const p = action.payload;
      return {
        ...state,
        sessionState: p.sessionState,
        trackingFailure: p.trackingFailure,
        elapsedMs: p.elapsedMs,
        canStop: p.canStop,
        trail: appendTrail(state.trail, p.trailAppend ?? []),
        position: p.position ?? state.position,
        pathLengthM: p.pathLengthM,
        keyframeCount: p.keyframeCount,
        maxSessionMs: p.maxSessionMs,
        warnBeforeEndMs: p.warnBeforeEndMs,
        coverageMethod: p.coverageMethod ?? state.coverageMethod,
        coverageFraction: p.coverageFraction ?? state.coverageFraction,
        coverageObserved: p.coverageObserved ?? state.coverageObserved,
        distanceToSurfaceM: p.distanceToSurfaceM ?? null,
      };
    }
    case 'signal':
      return { ...state, signal: action.payload };
    case 'error':
      return { ...state, error: action.message, phase: state.phase === 'starting' ? 'preview' : state.phase };
    default:
      return state;
  }
}

/**
 * The honest coverage label (TDD 7.3) is shown only once the model has measured enough surfaces
 * and only for methods that measure parallax; never for POSE_TRAIL.
 */
export function coverageVisible(s: CaptureState): boolean {
  return s.coverageMethod !== 'POSE_TRAIL' && s.coverageMethod !== 'NONE' && s.coverageObserved >= 30;
}

/** True during the final minute before the enforced maximum session length (TDD 5.3). */
export function nearSessionLimit(s: CaptureState): boolean {
  return s.phase === 'recording' && s.elapsedMs >= s.maxSessionMs - s.warnBeforeEndMs;
}
