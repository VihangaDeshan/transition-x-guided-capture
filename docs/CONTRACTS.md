# Data contracts and bridge

The three contracts are in `modules/guided-capture/android/src/main/java/lk/transitionx/capture/core/Contracts.kt`
and follow TDD v1.0 section 7. They contain no Android, ARCore or React Native types.

## Additions to SignalFrame (v1.0, additive)

The decision engine receives **no poses** (it consumes SignalFrame only), but G2, G3, G4 and C6 need
windowed motion figures. They are measured in the analysis layer and carried as extra fields, after
the TDD fields:

| Field | Meaning | Used by |
|---|---|---|
| `imageAgeMs` | Age of the carried-forward image measurements; -1 before the first sample | L1, L2, L3 ignore stale images (thermal suspension) |
| `windowRotationDeg` | Rotation accumulated over the spin window (2 s), decimated at 100 ms to ignore shake | G4 |
| `windowTranslationM` | Net translation over the spin window | G4 |
| `jitterDegPerSec` | Full-rate minus decimated rotation per second (hand shake) | G2 |
| `keyframeCount` | Keyframes accepted (≥ `baselineMinM` travelled) | G3, C6 |
| `pathLengthM` | Sum of keyframe baselines | C6 |
| `maxDistanceFromStartM` | Spatial extent of the walk | C6 |
| `textureFraction` (v1.1) | Share of 8×8 blocks with luminance std > 8; -1 if unknown | S1 |
| `sharpness` (v1.1) | Laplacian variance ÷ (mean block variance + 1); -1 if unknown | L3, L4 |
| `distanceToSurface` (v1.2) | median depth at the view centre; null without depth | S2, S3 |
| `angularAccel` (v1.2) | change of angular velocity, °/s² | G5 |
| `afState` (v1.2) | Camera2 CONTROL_AF_STATE; -1 unknown | L5 |
| `specularPercent` (v1.2) | % of 8×8 blocks with a glare pixel inside a darker block | S4 |
| `distanceFromStartM` (v1.2) | current distance from the start position | C5 |
| `coverageMethodCode`, `coverageObserved`, `coverageCovered` (v1.2) | coverage model state | C6, UI |
| `unseenArcDeg`, `unseenArcYawDeg` (v1.2) | largest never-viewed horizontal arc and signed turn to it | C1 |
| `lookedUp`, `lookedDown` (v1.2) | pitch beyond ±30° ever seen | C2 |
| `viewVoxelCount`, `viewSingleAngleFraction` (v1.2) | voxels in the view cone, share seen from one angle | C3 |
| `missingCornerYawDeg` (v1.2) | signed turn to a footprint corner with no observations | C4 |

`GuidanceState` gains `direction` (LEFT/RIGHT/UP/DOWN/BEHIND) for C1, C2 and C4, and `coverage`
now carries the measured coverage. New rule IDs: **T7** (redo a poor stretch, tier 3) and **G5**
(move smoothly, tier 4). The full explanation is in `docs/GUIDE.md`.

## Engine behaviour summary

- **Tiers** (lower = more urgent) and catalogue order give a deterministic single choice.
- **State machine** `IDLE → INITIALIZING → SCANNING ⇄ TRACKING_LOST → REVIEWING`, with the permitted
  rule set per state in one table (`PermittedRules`). SCANNING requires 1 s of stable tracking;
  TRACKING_LOST requires 0.5 s of lost tracking (T6 can show inside that grace window).
- **No same-tier pre-emption** (v1.1): a message that is still true is kept until a strictly more
  urgent tier appears.
- **Hysteresis** via separate enter/stay predicates; **debounce** 3 frames (not for ARCore-reported conditions);
  **minimum display** 1.5 s (tier 0 and state changes pre-empt); **cooldown** 3 shows per 10 s; the
  3rd show is **escalated**; **settling** 1 s after entering SCANNING and 0.5 s after recording start.
- **C6 / canStop** (pose-trail proxy until angular coverage exists): ≥ 45 s scanning, ≥ 6 m path,
  ≥ 30 keyframes, ≥ 1.0 m from start. Latched once true.
- **S1** uses `textureFraction` (not the ARCore point count) and **L3** uses `sharpness` while the
  phone is moving — see `docs/analysis/2026-10-24-first-device-capture.md`.
- Threshold files are validated: inverted hysteresis, negative timings and absurd limits are clamped.
- Time is a parameter — the engine never reads a clock (NFR-13). Replay uses frame timestamps.

## Bridge (TDD 12)

| Direction | Name | Payload |
|---|---|---|
| RN → native | `checkSupport()` | ARCore availability, camera permission, free bytes, device |
| RN → native | `requestArCoreInstall()` | Play Store install flow |
| RN → native | `startCapture(config)` | thresholds JSON, appVersion, debugSignals |
| RN → native | `stopCapture()` | → SessionSummary |
| RN → native | `acceptSession(id)` / `discardSession(id)` / `sharePackage(id)` | package, delete, share sheet |
| native → RN | `guidanceChanged` | GuidanceState (only on change) |
| native → RN | `captureStatus` | ~4 Hz: state, tracking, elapsed, trail points added since last event |
| native → RN | `signalFrame` | ~5 Hz, only with Debug on |
| native → RN | `sessionCompleted` | summary (also after auto-stop at the time limit) |
| native → RN | `captureError` | code + message |
