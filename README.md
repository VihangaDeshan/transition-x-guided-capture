# Guided Capture — Transition X × Rysera Innovations

Real-time guidance for recording an indoor space with an ordinary Android phone, so that the
recording is usable by Rysera's 3D reconstruction pipeline. Implements the **Guided Capture
Technical Design Document v1.0** (27 Sep 2026).

- **React Native (Expo SDK 54, RN 0.81, New Architecture)** — screens, state, strings (English + Sinhala), transfer workflow
- **Kotlin native module** (`modules/guided-capture`) — ARCore session, frame loop, Recording API, signal analysis, decision engine, export package
- **Apache-2.0**, permissively licensed dependencies only

> The app captures and guides. It does **not** reconstruct (TDD section 3).

## Install on a phone (no Android Studio needed)

Every push to `main` builds a release APK on GitHub Actions and attaches it to a **GitHub Release**.

1. On the phone, open this repository → **Releases** → newest `Guided Capture build N`.
2. Tap the `.apk`. Allow "install unknown apps" for your browser when Android asks.
3. Open **Guided Capture**. The check screen verifies the camera permission, Google Play Services for AR and free storage.

Tested target: Samsung Galaxy A55 5G (ARCore supported). Any phone on Google's
[ARCore supported devices list](https://developers.google.com/ar/devices) should work.

## What it does

| Stage | Where | Notes |
|---|---|---|
| Support checks (FR-1) | `CheckScreen` + `CaptureController.checkSupport` | Unsupported device is an expected path with its own message |
| AR capture (Layer 1) | `CaptureController` (GL thread) | Pose, tracking state/failure, feature count, Y-plane sampling, intrinsics |
| Signal analysis (Layer 2) | `signals/` (pure Kotlin) | Laplacian-variance blur on 160×120, brightness, clipping, velocities, spin window, jitter, keyframes |
| Decision engine (Layer 3) | `guidance/` (pure Kotlin) | Tiers, state machine, hysteresis, debounce (3), min display (1.5 s), cooldown (3 / 10 s), escalation, settling |
| Interface (Layer 4) | `src/` (React Native) | One banner at a time, haptics by severity, status line, pose trail, debug overlay |
| Recording & logs (Layer 5) | `SessionLogger`, `ExportDocs` | `session.mp4` via ARCore Recording API, `poses.jsonl`, `quality.jsonl`, `alerts.jsonl`, `coverage.json`, `manifest.json`, `summary.json` |
| Transfer | `acceptSession` / `sharePackage` | Zip + Android share sheet (manual by design until Rysera provides an endpoint, TDD 16) |

Phase 1 rules implemented: **T1 T2 T4 T5 G4 G1 L1 L3 S1 G2 L2 T6 G3 C6**, plus **T3** (thermal) for
the thermal policy in TDD 5.3. Phase 2/3 identifiers are defined (stable IDs, strings) but not evaluated.

Coverage is reported as **POSE_TRAIL** — the path walked — and no percentage is shown, because the
obvious direction-based percentage rewards spinning in place (TDD 7).

## Repository layout

```
App.tsx                         screen switch (check → capture → summary)
src/                            React Native UI, reducer, strings (en/si), presentation
config/thresholds.v1.json       threshold set (data, versioned) — passed to native at start
modules/guided-capture/
  src/                          TypeScript bindings (module, view, payload types)
  android/src/main/java/lk/transitionx/capture/
    core/        data contracts (CaptureFrame, SignalFrame, GuidanceState)   — no platform types
    signals/     image + motion measurement                                 — no platform types
    guidance/    rules, state machine, decision engine, thresholds          — no platform types
    replay/      JSONL codec + offline replay/sweep harness                 — no platform types
    export/      manifest / coverage / summary documents                    — no platform types
    android/     ARCore, GL view, recording, logging, Expo bridge
  android/src/test/              JUnit tests for the pure packages
tools/engine-tests/              plain-JVM Gradle build that compiles the pure packages without Android
__tests__/                       Jest tests (UI logic + TypeScript↔Kotlin consistency checks)
docs/                            contracts, export format, device test plan, Antigravity prompt
```

## Develop

```bash
npm ci
npm run check                          # type check + Jest
gradle -p tools/engine-tests test      # Kotlin engine tests on any JDK 17 (no Android SDK)
```

Building locally needs JDK 17 and the Android SDK (`npx expo prebuild -p android && cd android && ./gradlew assembleRelease`).
Without them, push to GitHub and let CI build the APK. `android/` is generated (Continuous Native
Generation) and not committed; native behaviour lives in `modules/guided-capture` and `app.json`.

### Replay a recorded session offline (threshold calibration, TDD 19)

Copy `quality.jsonl` off the phone (from the shared zip), then:

```bash
gradle -p tools/engine-tests replay -Pquality=/path/to/quality.jsonl [-Pthresholds=/path/to/thresholds.json]
```

It runs the *same* engine code as the app, faster than real time, and prints the guidance timeline
and per-rule counts. `ReplayRunner.sweep` runs a threshold sweep for precision/recall once Rysera's
pass/fail labels are available.

## Status and open items

Thresholds are the TDD starting values and **are not calibrated** (`thresholdsVersion: 1.0-initial`).
Open questions for Rysera are listed in TDD section 23; the most important is pass/fail labels for
20–30 test captures. See `docs/DEVICE_TEST.md` for the on-phone test checklist.

## Licence

Apache-2.0 — see `LICENSE`. Google Play Services for AR is a device-side runtime dependency, not bundled.
