# Test and Validation Report: Guided Capture v1.2

| | |
|---|---|
| Project | Guided Capture on a Phone (TransitionX 2026, Rysera Innovations) |
| Team | RoboNet |
| Software version | App 1.2.0, release build 16 (24 Oct 2026), thresholds `1.2-phase23`, export format 1.2 |
| Reporting period | 06 Sep 2026 to 24 Oct 2026 |
| Reference device | Samsung Galaxy A55 5G (SM-A556E), Android 16 (API 36), ARCore 1.57 |

**Privacy.** The on-device capture was recorded in a private home. No images, video or capture
packages are published. This report and `docs/analysis/2026-10-24-first-device-capture.md` give
aggregate measurements only.

## 1. Test approach

| Level | What is tested | How |
|---|---|---|
| Unit (JVM) | Signal maths, coverage model, decision engine, state machine, replay, export documents | 67 JUnit tests on a plain JVM; no phone or Android SDK needed |
| Unit (JavaScript) | UI state, strings (English and Sinhala), TypeScript and Kotlin consistency, release signing | 27 Jest tests |
| Regression on real data | The engine replayed on the numeric signal log of the first on-device capture | `RealCaptureTest` (part of the 67) |
| Build | A signed release APK is built from a clean checkout on every change | GitHub Actions, published to GitHub Releases |
| On device | Frame rate, tracking, recording, measurement validity, guidance behaviour | Galaxy A55, 83.5 s capture, 24 Oct 2026 |

## 2. Automated test results (build 16)

| Suite | Tests | Covers | Result |
|---|---|---|---|
| `SignalsTest` | 10 | Brightness, clipping, sharpness, texture, glare, velocities, spin window, keyframes | Pass |
| `GuidanceEngineTest` | 16 | Priority tiers, hysteresis, 3-frame debounce, 1.5 s minimum display, cooldown, escalation, settling | Pass |
| `Phase23Test` | 21 | 20 cm voxel parallax coverage, depth rules, thermal, navigation rules (C1 to C5), redo rule T7, smooth motion G5 | Pass |
| `RealCaptureTest` | 12 | Replay of the real capture: false alarms removed, real problems still reported, determinism | Pass |
| `ReplayAndExportTest` | 8 | Log codec, offline replay, manifest, SHA-256 package integrity | Pass |
| Jest (6 files) | 27 | UI reducer, pose trail, strings, rule-ID consistency, Phase 2/3 presentation, signing config | Pass |
| **Total** | **94** | | **All pass** |

## 3. On-device results (Galaxy A55, 24 Oct 2026, app 1.0.0)

| Measurement | Result | Target | Status |
|---|---|---|---|
| Sustained frame rate | 30.0 fps; frame interval median 33.4 ms, 95th percentile 33.41 ms, max 46.3 ms | At least 25 fps (NFR-2) | Met |
| Analysis backpressure | 2504 of 2504 frames analysed; 626 image samples (every 4th frame) | No dropped analysis | Met |
| Tracking | Tracking for all 2504 frames; 0 losses | | Met |
| Thermal | Status 0 for the whole session | No throttling | Met (83 s only) |
| Recording | ARCore Recording API MP4 written and playable, H.264 plus IMU tracks; 640x480 at 8.5 Mbit/s; 90.8 MB for 83.5 s | Playable | Met; resolution raised in v1.1 (to verify) |
| Measurement validity | Blur and brightness recomputed from the recorded video correlate r = 0.994 and r = 0.993 with on-phone values | Consistent | Met |
| Determinism | Offline replay reproduced the phone's per-rule counts exactly | Identical (NFR-13) | Met |
| Rules seen on the phone | T2, G4, G1, L1, L3, S1, G3 (7 of 14 Phase 1 rules) | All Phase 1 rules | Partial |

## 4. Defects found on the device and their fixes

| Finding | Version 1.0 | After fix (same session, replayed) | Fix |
|---|---|---|---|
| Blank-wall warning (S1) false alarms | 10 shows | 0 | Image texture measure replaces the ARCore point count (0 to 11 points on this phone) |
| Blur warning (L3) on sharp, plain surfaces | 5 shows | 4, all during real motion smear | Contrast-normalised sharpness, and only while the phone is moving |
| Same-tier messages alternating | 6 or more swaps | 0 | A true message is only replaced by a strictly more urgent one |
| "Scan looks good" (C6) never reached | 0 | 1 (at 77 s) | Extent requirement set to 1.0 m |
| Storage estimate | 2.3 times too pessimistic | Measured 1.09 MB/s used | Pre-recording check uses the measured rate |

## 5. Synthetic validation of the coverage model

| Scenario (4 m room) | Coverage seen from two or more angles | Requirement |
|---|---|---|
| Spinning 360 degrees in place | 0% | Spinning must score 0%: met |
| Walking a 1 m circle | More than 40% | Walking must score well above spinning: met |

## 6. Not yet validated

1. On-device verification of the 20 rules not yet seen on the phone (checklist: `docs/DEVICE_TEST.md`).
2. 10-minute session: sustained frame rate and thermal behaviour (NFR-4).
3. Guidance latency and button response (NFR-1, NFR-3).
4. 720p recording on the A55 after the camera-configuration change.
5. Two to three further mid-range phone models.
6. Threshold calibration against Rysera pass/fail labels for 20 to 30 captures.
7. User study (10 to 15 untrained participants): guided against unguided capture, measured by
   reconstruction success rate.

Thresholds `1.2-phase23` are informed by one real session and synthetic tests. They are not yet calibrated.
