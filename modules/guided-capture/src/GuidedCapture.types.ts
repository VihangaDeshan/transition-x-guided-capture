// SPDX-License-Identifier: Apache-2.0
// TypeScript mirror of the bridge payloads. Field names match the Kotlin maps exactly.

export type RuleId =
  | 'T1' | 'T2' | 'T3' | 'T4' | 'T5' | 'T6'
  | 'G1' | 'G2' | 'G3' | 'G4'
  | 'L1' | 'L2' | 'L3' | 'L4' | 'L5'
  | 'S1' | 'S2' | 'S3' | 'S4'
  | 'C1' | 'C2' | 'C3' | 'C4' | 'C5' | 'C6';

export type Severity = 'INFO' | 'WARNING' | 'CRITICAL';
export type SessionState = 'IDLE' | 'INITIALIZING' | 'SCANNING' | 'TRACKING_LOST' | 'REVIEWING';
export type TrackingStatus = 'TRACKING' | 'PAUSED' | 'STOPPED';
export type TrackingFailure =
  | 'NONE' | 'EXCESSIVE_MOTION' | 'INSUFFICIENT_FEATURES' | 'INSUFFICIENT_LIGHT' | 'BAD_STATE' | 'CAMERA_UNAVAILABLE';

export type ArCoreAvailability =
  | 'SUPPORTED_INSTALLED' | 'SUPPORTED_APK_TOO_OLD' | 'SUPPORTED_NOT_INSTALLED'
  | 'UNSUPPORTED_DEVICE_NOT_CAPABLE' | 'UNKNOWN_CHECKING' | 'UNKNOWN_ERROR' | 'UNKNOWN_TIMED_OUT';

export interface CoveragePayload {
  method: 'NONE' | 'POSE_TRAIL' | 'POINTCLOUD_ANGULAR' | 'VOXEL_GRID';
  surfacesSeenFromTwoPlusAngles: number;
  observedVoxelCount: number;
  coveredVoxelCount: number;
}

/** guidanceChanged: emitted only when the selected rule, severity, escalation or state changes. */
export interface GuidancePayload {
  ruleId: RuleId | null;
  severity: Severity;
  tier: number | null;
  sessionState: SessionState;
  canStop: boolean;
  escalated: boolean;
  elapsedMs: number;
  coverage: CoveragePayload;
}

/** captureStatus: about 4 Hz. trailAppend carries only keyframe points added since the last event. */
export interface StatusPayload {
  sessionState: SessionState;
  trackingStatus: TrackingStatus;
  trackingFailure: TrackingFailure;
  elapsedMs: number;
  canStop: boolean;
  keyframeCount: number;
  pathLengthM: number;
  maxDistanceFromStartM: number;
  thermalStatus: number;
  position: [number, number];
  trailAppend: [number, number][];
  maxSessionMs: number;
  warnBeforeEndMs: number;
}

/** signalFrame: about 5 Hz, only when debug signals are enabled. */
export interface SignalPayload {
  t: number;
  blurScore: number;
  brightness: number;
  clippedHigh: number;
  clippedLow: number;
  angularVelocity: number;
  linearVelocity: number;
  featureCount: number;
  baseline: number;
  trackingOk: boolean;
  failureReason: TrackingFailure;
  thermalStatus: number;
  imageAgeMs: number;
  windowRotationDeg: number;
  windowTranslationM: number;
  jitterDegPerSec: number;
  keyframeCount: number;
  pathLengthM: number;
  sampleInterval: number;
  imageSuspended: boolean;
}

export type Verdict = 'GOOD' | 'CHECK' | 'INCOMPLETE';

export interface SessionSummary {
  sessionId: string;
  durationMs: number;
  verdict: Verdict;
  canStop: boolean;
  trackingLostCount: number;
  trackingLostTotalMs: number;
  warningsByRule: Partial<Record<RuleId, number>>;
  suppressedByRule: Partial<Record<RuleId, number>>;
  coverageMethod: string;
  pathLengthM: number;
  keyframeCount: number;
  maxDistanceFromStartM: number;
  interruptions: number;
  recordingOk: boolean;
  recordingError: string | null;
  endReason: 'USER_STOP' | 'MAX_DURATION' | 'VIEW_DETACHED' | string;
  path: string;
  trail: [number, number][];
}

export interface SupportInfo {
  arcore: ArCoreAvailability;
  cameraPermission: boolean;
  freeBytes: number;
  device: string;
  sdkInt: number;
  arcoreApkVersion: string | null;
}

export interface StartResult {
  sessionId: string;
  recording: boolean;
  recordingError: string | null;
  path: string;
}

export interface AcceptResult {
  sessionId: string;
  packagePath: string;
  zipPath: string;
  zipBytes: number;
  transfer: 'MANUAL' | 'QUEUED';
}

export interface CaptureErrorPayload {
  code: string;
  message: string;
}

export interface StartConfig {
  thresholds: Record<string, number | string>;
  appVersion: string;
  debugSignals?: boolean;
  estimatedBytesPerSecond?: number;
}

export type GuidedCaptureEvents = {
  guidanceChanged: (e: GuidancePayload) => void;
  captureStatus: (e: StatusPayload) => void;
  signalFrame: (e: SignalPayload) => void;
  sessionCompleted: (e: SessionSummary) => void;
  captureError: (e: CaptureErrorPayload) => void;
};
