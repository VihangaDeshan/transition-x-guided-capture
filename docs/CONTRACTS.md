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

## Engine behaviour summary

- **Tiers** (lower = more urgent) and catalogue order give a deterministic single choice.
- **State machine** `IDLE → INITIALIZING → SCANNING ⇄ TRACKING_LOST → REVIEWING`, with the permitted
  rule set per state in one table (`PermittedRules`). SCANNING requires 1 s of stable tracking;
  TRACKING_LOST requires 0.5 s of lost tracking (T6 can show inside that grace window).
- **Hysteresis** via separate enter/stay predicates; **debounce** 3 frames (not for ARCore-reported conditions);
  **minimum display** 1.5 s (tier 0 and state changes pre-empt); **cooldown** 3 shows per 10 s; the
  3rd show is **escalated**; **settling** 1 s after entering SCANNING and 0.5 s after recording start.
- **C6 / canStop** (pose-trail proxy until angular coverage exists): ≥ 45 s scanning, ≥ 6 m path,
  ≥ 30 keyframes, ≥ 1.5 m from start. Latched once true.
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
