# Guided Capture — the complete guide

This guide explains the whole system step by step, from "why does the app exist" down to the
formula behind every warning. Read it top to bottom once; afterwards use the table of contents.
Diagrams are Mermaid and render on GitHub.

1. [The problem in one picture](#1-the-problem-in-one-picture)
2. [The system at a glance](#2-the-system-at-a-glance)
3. [What happens when you press Record](#3-what-happens-when-you-press-record)
4. [Threads: who does what, and why](#4-threads-who-does-what-and-why)
5. [Layer 1 — capture: what is read from ARCore](#5-layer-1--capture-what-is-read-from-arcore)
6. [Layer 2 — signals: turning frames into numbers](#6-layer-2--signals-turning-frames-into-numbers)
7. [The coverage model: measuring parallax, not directions](#7-the-coverage-model-measuring-parallax-not-directions)
8. [Layer 3 — the decision engine](#8-layer-3--the-decision-engine)
9. [The complete rule catalogue (27 rules)](#9-the-complete-rule-catalogue-27-rules)
10. [Layer 4 — the interface](#10-layer-4--the-interface)
11. [Layer 5 — recording, logs and the export package](#11-layer-5--recording-logs-and-the-export-package)
12. [Calibration with offline replay](#12-calibration-with-offline-replay)
13. [Security and privacy](#13-security-and-privacy)
14. [How it is tested](#14-how-it-is-tested)
15. [Recipes: changing the system safely](#15-recipes-changing-the-system-safely)
16. [Limits and what comes next](#16-limits-and-what-comes-next)

---

## 1. The problem in one picture

A 3D reconstruction pipeline recovers depth by **triangulation**: the same point must be seen
from two *different places*. Untrained people scan a room the way they take a panorama: they
stand in the middle and turn. Every frame overlaps nicely, so the video *looks* fine, but there is
no parallax, so nothing can be triangulated and the reconstruction fails.

```mermaid
flowchart LR
    subgraph BAD["Spinning on the spot (what people do)"]
        direction TB
        c1(("📱 one place")) -->|ray 1| p1["wall point"]
        c1 -->|ray 2, same line| p1
    end
    subgraph GOOD["Walking around (what reconstruction needs)"]
        direction TB
        a(("📱 place A")) -->|ray A| p2["wall point"]
        b(("📱 place B")) -->|ray B| p2
    end
    BAD -.->|"0° between rays → no depth"| X["❌ fails"]
    GOOD -.->|"angle between rays → depth"| Y["✅ works"]
```

The app watches the capture as it happens and corrects behaviour **at the moment it occurs**,
with one short instruction at a time. Fixing it later means another visit to the house.

---

## 2. The system at a glance

```mermaid
flowchart TB
    subgraph RN["React Native (TypeScript) — what the user sees"]
        UI["Screens: Check → Capture → Summary"]
        STR["Strings en / සිංහල (rule ID → sentence)"]
        VOICE["Voice policy + haptics"]
    end
    subgraph BRIDGE["Expo native module (narrow bridge)"]
        CMD["commands ↓ start / stop / accept / share / discard"]
        EVT["events ↑ guidanceChanged · captureStatus · signalFrame · sessionCompleted"]
    end
    subgraph NATIVE["Kotlin — runs at camera frame rate"]
        L1["Layer 1 Capture<br/>ARCore session, GL view, recording"]
        L2["Layer 2 Signals<br/>image + motion + coverage"]
        L3["Layer 3 Decision engine<br/>rules, tiers, state machine, timing"]
        L5["Layer 5 Export<br/>logs, manifest, checksums, zip"]
    end
    UI --> CMD --> L1
    L1 -->|CaptureFrame| L2 -->|SignalFrame| L3 -->|GuidanceState| EVT --> UI
    L1 --> L5
    L2 --> L5
    L3 --> L5
    STR --> UI
    VOICE --> UI
```

**Why this split?** ARCore has no JavaScript API, and the per-frame loop runs 30 times a second
on mid-range phones, so everything that touches frames stays in Kotlin. React Native owns screens,
text and the transfer workflow, which change often and can later be shared with iOS.

**The rule that keeps it testable:** the packages `core`, `signals`, `guidance`, `replay` and
`export` contain no Android, ARCore or React Native types. They compile on a plain JVM
(`tools/engine-tests`), so the whole decision logic is unit-tested and replayed on a laptop.

```
modules/guided-capture/android/src/main/java/lk/transitionx/capture/
├── core/      Contracts.kt — CapturePose, CaptureFrame, SignalFrame, GuidanceState, rule IDs
├── signals/   ImageMetrics, MotionTracker, CoverageModel, SignalAnalyzer
├── guidance/  Rules, Thresholds, SessionStateMachine, GuidanceEngine
├── replay/    JsonLite, LogCodec, ReplayRunner (offline replay + threshold sweep)
├── export/    ExportDocs (manifest/coverage/summary), PackageIntegrity (SHA-256 + zip)
└── android/   CaptureController, GuidedCaptureView, BackgroundRenderer, CoverageOverlayRenderer,
               FrameSampler, AnalysisPipeline, SessionLogger, PackageExporter, Speech, Module
```

---

## 3. What happens when you press Record

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant JS as React Native
    participant M as Native module
    participant C as CaptureController (GL thread)
    participant A as AnalysisPipeline (analysis thread)
    participant W as SessionLogger (writer thread)

    U->>JS: open Capture screen
    JS->>M: mount GuidedCaptureView
    M->>C: create ARCore session (30 fps config, depth if supported), resume
    U->>JS: tap Record
    JS->>M: startCapture(thresholds, appVersion)
    M->>C: free-space check, create scan_<id>/, startRecording(session.mp4)
    loop every camera frame (~33 ms)
        C->>C: session.update(), draw camera, read pose + tracking + AF state
        C->>C: every 4th frame: copy Y plane, sample depth + points
        C->>W: pose line (poses.jsonl)
        C->>A: CaptureFrame (single-slot mailbox, drop-not-queue)
        A->>A: SignalAnalyzer → SignalFrame, CoverageModel update
        A->>A: GuidanceEngine.evaluate(signal, time)
        A->>W: quality.jsonl, alerts.jsonl
        A-->>JS: guidanceChanged (only on change), captureStatus (4 Hz)
        C->>C: draw stripes over one-angle voxels
    end
    JS->>U: one banner + vibration (+ voice if on)
    U->>JS: tap Stop
    JS->>M: stopCapture()
    M->>C: stopRecording, finish analysis, flush logs
    M->>M: write coverage.json, summary.json, manifest.json
    M-->>JS: SessionSummary
    U->>JS: Use this capture
    JS->>M: acceptSession → zip + checksums.sha256 → share sheet
```

---

## 4. Threads: who does what, and why

```mermaid
flowchart LR
    GL["GL thread<br/>session.update()<br/>draw camera + stripes<br/>copy Y plane, depth samples"]
    AN["Analysis thread<br/>image metrics<br/>motion + coverage<br/>decision engine"]
    WR["Writer thread<br/>buffered JSONL"]
    JST["JS thread<br/>React render"]
    GL -->|"latest frame only<br/>(older pending frame dropped)"| AN
    GL -->|pose lines| WR
    AN -->|signals, alerts| WR
    AN -->|events| JST
```

| Rule | Reason |
|---|---|
| Image analysis never runs on the GL thread | it would stall `session.update()` and drop the frame rate |
| Frames are dropped, never queued | a queue turns a CPU spike into permanently late guidance |
| Logging is buffered on its own thread | per-frame file I/O would dominate the frame budget |
| The engine never reads a clock | deterministic, testable, and replayable faster than real time |

Measured on the Galaxy A55 (first real capture): **30.0 fps**, frame interval p95 33.4 ms, max 46 ms,
2504 poses → 2504 analysed frames (nothing dropped).

---

## 5. Layer 1 — capture: what is read from ARCore

| Every frame | Every 4th frame (sampled) |
|---|---|
| Camera pose (position + quaternion) | Y plane of the CPU image (640×480 on the A55) |
| Tracking state + failure reason | Depth image (if supported): 24×18 grid → world points + centre distance |
| Autofocus state (`CONTROL_AF_STATE`) | Confident ARCore feature points (≤ 200) |
| Point-cloud size | |
| Camera intrinsics (once) | |

Camera configuration: the highest **CPU image** resolution up to 1280×720 at 30 fps, because the
ARCore Recording API stores the CPU image stream (the first capture was 640×480 even though the
display texture was 1080p). The chosen size is written to the manifest.

---

## 6. Layer 2 — signals: turning frames into numbers

### 6.1 Image signals (160×120 box-averaged copy of the Y plane)

| Signal | How it is computed | Catches |
|---|---|---|
| `brightness` | mean of Y (Y *is* perceptual luminance; no RGB conversion) | L1 too dark |
| `clippedHigh` / `clippedLow` | % pixels > 250 / < 5 | L2 too bright |
| `blurScore` | variance of the 3×3 Laplacian | (raw detail; legacy) |
| `textureFraction` | share of 8×8 blocks with std-dev > 8 | S1 blank wall |
| `sharpness` | Laplacian variance ÷ (mean 8×8 block variance + 1) | L3 motion blur, L4 lens |
| `specularPercent` | % blocks with a pixel ≥ 250 while the block mean < 170 | S4 glare / glass |

Why `sharpness` instead of raw Laplacian variance? A plain ceiling is sharp but has little detail,
so raw variance called it "blurry". Dividing by block contrast asks "is the detail that exists
crisp?". On the real capture the median was **0.67 while turning > 30°/s and 1.93 while still**.

### 6.2 Motion signals (from poses)

| Signal | How | Catches |
|---|---|---|
| `angularVelocity`, `linearVelocity` | change over a 200 ms window | G1 turn too fast |
| `angularAccel` | change of angular velocity over 200 ms | G5 jerky |
| `windowRotationDeg` / `windowTranslationM` | rotation accumulated (100 ms steps) vs net translation over 2 s | G4 spinning |
| `jitterDegPerSec` | full-rate rotation − decimated rotation, per second | G2 shake |
| keyframes, `baseline`, `pathLengthM` | a keyframe every 0.15 m travelled | G3 keep moving, C6 |
| `distanceFromStartM`, `maxDistanceFromStartM` | from the first tracked pose | C5 loop, C6 |

While tracking is lost the pose history is cleared, so a relocalisation jump never looks like a
violent motion.

### 6.3 Depth and focus

* `distanceToSurface`: median depth in the centre third of the depth image (S2/S3). If the phone
  has no Depth API the value is **absent**, and the rules stay silent instead of guessing.
* `afState`: Camera2 autofocus state (1 / 3 = scanning) for L5.

---

## 7. The coverage model: measuring parallax, not directions

The obvious coverage metric ("how many directions have I looked in") is **wrong**: it is
highest for the user who spins in place, the worst possible capture. The app measures what
reconstruction needs instead.

```mermaid
flowchart TB
    P["3D points this frame<br/>(depth grid + ARCore points)"] --> V["Find the 20 cm voxel of each point"]
    V --> D["Direction camera → voxel CENTRE"]
    D --> N{"voxel seen before?"}
    N -- no --> O["mark OBSERVED<br/>remember first direction"]
    N -- yes --> A{"angle to first direction ≥ 15°?"}
    A -- yes --> C["mark COVERED<br/>(seen from two places)"]
    A -- no --> K["still one-angle<br/>(drawn as stripes)"]
```

Because the direction is taken to the voxel **centre**, turning on the spot never changes it.
Tested on a synthetic 4 m room: spinning 360° → many observed voxels, **0 % covered**; walking a
1 m circle → **> 40 % covered** (`CoverageModelTest`).

The same model answers the navigation questions:

| Rule | Question | Measured as |
|---|---|---|
| C1 | Which part of the room have I never looked at? | 12 azimuth bins of viewing direction; largest unseen arc ≥ 60°, turn ≥ 45° |
| C2 | Have I looked up and down? | pitch beyond ±30° ever seen |
| C3 | Is what I see now seen from one angle only? | voxels in a 30° cone; ≥ 70 % one-angle for 3 s |
| C4 | Is a room corner missing? | corners of the observed floor footprint with no voxel within 0.75 m |
| C6 | Is it good enough to stop? | time + path + keyframes + extent, **and** ≥ 50 % covered once ≥ 60 voxels are measured |

Method reported honestly: `VOXEL_GRID` (depth used), `POINTCLOUD_ANGULAR` (ARCore points only),
or `POSE_TRAIL` (nothing measured). The percentage is shown only for the first two, labelled
"Seen from 2+ angles".

**"Erase the stripes"** (IntelliCap, ISMAR 2025): one-angle voxels are drawn as pink-and-white
striped discs over the camera image. Walk to see them from a second place and they disappear.

---

## 8. Layer 3 — the decision engine

Several rules are true at the same time in normal use. Showing all of them trains the user to
ignore the screen, so the engine shows **exactly one**, and keeps it stable.

### 8.1 Session state machine

```mermaid
stateDiagram-v2
    [*] --> IDLE
    IDLE --> INITIALIZING: Record
    INITIALIZING --> SCANNING: tracking stable 1 s
    SCANNING --> TRACKING_LOST: tracking lost 0.5 s
    TRACKING_LOST --> SCANNING: stable again 1 s
    INITIALIZING --> REVIEWING: Stop
    SCANNING --> REVIEWING: Stop / 10-min limit
    TRACKING_LOST --> REVIEWING: Stop
    note right of INITIALIZING: only T2 (+T4/T5)
    note right of TRACKING_LOST: only T1 (+T4/T5)
    note left of SCANNING: all other rules
```

The permitted set is a table owned by the state machine, so a rule added later cannot "forget"
to check the state.

### 8.2 One evaluation, step by step

```mermaid
flowchart TB
    S["SignalFrame + time"] --> SM["Update state machine"]
    SM --> X["Engine state: canStop, loop closed, poor-segment flag"]
    X --> R["For each rule permitted in this state"]
    R --> H{"active already?"}
    H -- no --> E["enter condition"]
    H -- yes --> T["stay condition (looser = hysteresis)"]
    E --> DB["debounce: 3 frames in a row"]
    DB --> SU["sustain: held for sustainMs (L4 5 s, C3 3 s, …)"]
    SU --> ACT["ACTIVE rules"]
    T --> ACT
    ACT --> Q{"settling? (1 s after SCANNING, 0.5 s after Record)"}
    Q --> CD["cooldown: ≤ 3 shows per 10 s"]
    CD --> PR["pick lowest tier, then catalogue order"]
    PR --> MD{"current message shown < 1.5 s<br/>or still true and same/higher urgency?"}
    MD -- keep --> OUT["GuidanceState (+ direction arrow)"]
    MD -- switch --> OUT
    OUT --> LOG["alerts.jsonl: SHOWN / DISMISSED / SUPPRESSED"]
```

### 8.3 Priority tiers

| Tier | Meaning | Rules |
|---|---|---|
| 0 | Session blocking | T1 T2 T4 T5 |
| 1 | Structurally unusable | G4 |
| 2 | Data being destroyed | G1 L1 L3 S1 |
| 3 | Data degraded | G2 L2 T6 S2 T7 |
| 4 | Efficiency / device / surface | G5 L4 L5 S3 T3 S4 |
| 5 | Completeness | G3 C3 C5 |
| 6 | Navigation | C1 C2 C4 |
| 7 | Positive | C6 |

Severity follows the tier (0–1 critical, 2–3 warning, 4–7 info) and sets colour, vibration and
voice. A message that is still true is not replaced by another of the same tier (found on the
first real capture, where S1 and L3 alternated every 1.5 s).

---

## 9. The complete rule catalogue (27 rules)

Thresholds are in `config/thresholds.v1.json` (version `1.2-phase23`). "Real" = observed on the
Galaxy A55 capture; "Synthetic" = verified by unit test only.

| ID | Message (EN) | Trigger | Tier | Phase | Evidence |
|---|---|---|---|---|---|
| T1 | Tracking lost — go back to a scanned area (+ reason) | state TRACKING_LOST | 0 | 1 | Synthetic |
| T2 | Move the phone slowly side to side | state INITIALIZING | 0 | 1 | Real |
| T4 | Another app is using the camera | ARCore CAMERA_UNAVAILABLE | 0 | 1 | Synthetic |
| T5 | Something went wrong — restart | ARCore BAD_STATE | 0 | 1 | Synthetic |
| G4 | Walk around the room, don't spin | rotation > 30° with < 0.10 m in 2 s | 1 | 1 | Real |
| G1 | Turn more slowly | angular velocity > 45 °/s (exit 35) | 2 | 1 | Real |
| L1 | Too dark — turn on a light | brightness < 40 (exit 50) or ARCore INSUFFICIENT_LIGHT | 2 | 1 | Real |
| L3 | Blurry — move more slowly | sharpness < 1.0 (exit 1.3) **while moving** | 2 | 1 | Real |
| S1 | Blank wall — look at corners / objects | texture < 0.15 (exit 0.25) in good light, or INSUFFICIENT_FEATURES | 2 | 1 | Real (false alarms removed) |
| G2 | Hold the phone steady | jitter > 10 °/s (exit 6) while < 0.3 m/s | 3 | 1 | Synthetic |
| L2 | Too bright — avoid the window | clipped > 15 % (exit 10 %) | 3 | 1 | Synthetic |
| T6 | You're moving too fast | ARCore EXCESSIVE_MOTION (grace window) | 3 | 1 | Synthetic |
| S2 | Too close — step back | depth < 0.5 m (exit 0.6) | 3 | 2 | Synthetic |
| T7 | That part was poor — film it again | ≥ 60 % of 5 s lost/dark/blurred, then 1 s good; one-shot, ≥ 20 s apart | 3 | 2 | Real (once, after the dark spell) |
| G5 | Move smoothly | angular acceleration > 400 °/s² (exit 250) | 4 | 2 | Synthetic |
| L4 | Clean the camera lens | still + good light + detailed + sharpness < 1.0, focus settled, 5 s | 4 | 2 | Synthetic |
| L5 | Hold still — camera is focusing | AF scanning + soft image, 1 s | 4 | 2 | Synthetic |
| S3 | Too far — move closer | depth > 4.0 m (exit 3.5) | 4 | 2 | Synthetic |
| T3 | Phone is hot — finish soon | thermal ≥ SEVERE | 4 | 2 | Synthetic |
| S4 | Mirror or glass — move slowly (experimental) | glare blocks 2–15 %, not a window, 1.5 s | 4 | 3 | Synthetic |
| G3 | Keep moving forward | no new keyframe for 3 s | 5 | 1 | Real |
| C3 | Film this from another spot — erase the stripes | ≥ 70 % of voxels in view one-angle, 3 s, after 15 s | 5 | 2 | Synthetic |
| C5 | Walk back to where you started | walked ≥ 2.5 m away and ≥ 6 m path, after 40 s, not returned within 1 m | 5 | 2 | Synthetic |
| C1 | Turn left ← / right → / around ↻ | unseen arc ≥ 60° and turn ≥ 45°, after 15 s | 6 | 2 | Synthetic |
| C2 | Look up ↑ / down ↓ | never pitched beyond ±30°, after 30 s | 6 | 2 | Synthetic |
| C4 | A corner is missing ← / → / ↻ | footprint ≥ 2×2 m, corner without voxels, ≥ 150 voxels, after 45 s | 6 | 3 | Synthetic |
| C6 | Scan looks good — you can stop | ≥ 45 s, ≥ 6 m, ≥ 30 keyframes, ≥ 1.0 m extent, ≥ 50 % covered when measured | 7 | 1 | Real (replay) |

Every escalated (3rd) occurrence shows a longer explanation where one exists.

---

## 10. Layer 4 — the interface

```mermaid
flowchart LR
    CK["Check screen<br/>camera permission · ARCore · storage<br/>EN/සිංහල toggle"] -->|Start scan| CP["Capture screen<br/>camera + stripes · banner · REC/time<br/>coverage % · pose trail · Voice/Stripes/Debug"]
    CP -->|Stop / 10-min limit| SM["Summary<br/>verdict · warnings by rule · coverage · loop"]
    SM -->|Record again| CP
    SM -->|Use this capture| EX["Packaged<br/>Share · Delete from phone"]
    EX --> CK
```

* **Banner**: one message, colour by severity, arrow for C1/C2/C4, fuller text when escalated,
  reason line under T1.
* **Haptics**: critical → one long pulse, warning → two short pulses.
* **Voice** (toggle): the phone's own TextToSpeech; speaks only when the message changes and not
  the same one within 8 s; Sinhala if the phone has a Sinhala voice, otherwise the English text.
* **Stripes** (toggle): the coverage overlay.
* **Debug** (toggle): every signal the engine sees, so a tester can tell *why* a message appeared.
* **Coverage %**: shown only when measured (`VOXEL_GRID` / `POINTCLOUD_ANGULAR`, ≥ 30 voxels).

---

## 11. Layer 5 — recording, logs and the export package

```
scan_<uuid>/                      formatVersion 1.2
├── session.mp4        ARCore recording (CPU image stream + IMU tracks)
├── poses.jsonl        every frame: timestamp, position, quaternion, tracking   ← primary pose record
├── quality.jsonl      every analysed frame: the full SignalFrame               ← replay input
├── alerts.jsonl       SHOWN / DISMISSED / SUPPRESSED / STATE / INTERRUPTION
├── coverage.json      method, % seen from 2+ angles, every voxel (x,y,z,parallax,hits), pose trail
├── summary.json       verdict, warnings by rule, tracking losses, coverage, loop closed
├── manifest.json      device, intrinsics, resolution, depth used, versions, integrity
├── status.json        accepted + transfer mode            (written on accept)
└── checksums.sha256   SHA-256 of every file               (written on accept)
```

Verify on a computer: `cd scan_<id> && sha256sum -c checksums.sha256`. Details:
`docs/EXPORT_FORMAT.md`.

---

## 12. Calibration with offline replay

Thresholds are guesses until compared with outcomes. The engine is deterministic and takes time
as a parameter, so a recorded session can be replayed through the **same code** on a laptop.

```mermaid
flowchart LR
    R["Record 20–30 captures<br/>(good + deliberately bad)"] --> P["Rysera pipeline<br/>pass / fail labels"]
    R --> Q["quality.jsonl"]
    Q --> RP["ReplayRunner<br/>(faster than real time)"]
    TH["thresholds.json<br/>candidate values"] --> RP
    RP --> SW["sweep: warnings per rule<br/>per threshold value"]
    P --> PR["precision / recall"]
    SW --> PR
    PR --> V["commit new thresholds<br/>version bump"]
```

```bash
gradle -p tools/engine-tests replay -Pquality=/path/quality.jsonl [-Pthresholds=/path/thresholds.json]
```

The first real capture is already a regression fixture (`RealCaptureTest`): it proves the
v1.1/v1.2 rules remove the false alarms and still report the real problems.

---

## 13. Security and privacy

A capture is a video of the inside of a home. Summary (full list in `SECURITY.md`):
least permissions (storage and draw-over-apps removed), no cloud backup, only finished zips
exposed through the FileProvider, no network code or analytics, delete-from-phone, validated
session IDs (no path traversal), clamped threshold input, SHA-256 integrity, read-only CI token,
optional release signing from secrets, licence audit, Dependabot.

---

## 14. How it is tested

| Level | What | Where |
|---|---|---|
| Unit (JVM) | signals, coverage model, every rule, engine timing, codec, export, integrity, validation — 67 tests | `modules/guided-capture/android/src/test`, run by `tools/engine-tests` |
| Real-data regression | the A55 capture replayed: no S1 false alarms, real problems reported, T7 once, no ping-pong | `RealCaptureTest` |
| JS unit | strings complete (en/si, directional), reducer, trail, voice policy, signing plugin, TS↔Kotlin consistency — 27 tests | `__tests__/` |
| Purity | pure packages import no platform types | Jest + JVM build |
| Build | Expo prebuild + Gradle release APK from a clean clone | GitHub Actions |
| Device | manual checklist per rule | `docs/DEVICE_TEST.md` |

---

## 15. Recipes: changing the system safely

**Change a threshold:** edit `config/thresholds.v1.json`, bump `version`, replay a recorded
session, push. No code change; the manifest records which version produced each capture.

**Change wording or add a language:** edit `src/i18n/strings.ts` only. Tests fail if any rule or
arrow is missing a sentence.

**Add a rule:**
1. Add the ID with its tier and phase to `GuidanceRuleId` (`core/Contracts.kt`).
2. Add the measured number to `SignalFrame` if needed (and to `LogCodec`, defaulting to "unknown").
3. Add a `Rule` in `guidance/Rules.kt` (enter, looser stay, `sustainMs`, optional `direction`).
4. Permit it in `PermittedRules` for the right state.
5. Add the tier to `src/guidance/rules.ts`, text to `strings.ts`.
6. Add a JUnit test in `Phase23Test.kt`; run `npm run check` and `gradle -p tools/engine-tests test`.

---

## 16. Limits and what comes next

* **Calibration**: values are informed by one real session and synthetic tests. The decisive input
  is Rysera's pass/fail labels for 20–30 captures.
* **Depth**: S2/S3 and `VOXEL_GRID` need a phone with the ARCore Depth API; otherwise they are
  silent and coverage uses the (sparse) ARCore points.
* **S4 reflections** is a heuristic, marked experimental.
* **Overlay occlusion**: stripes are drawn without a depth test, so voxels behind a wall can show
  through.
* **iOS**: the interface ports; ARKit needs a separate native module (TDD 5.5).
* **User study** (TDD 20): with/without guidance, 10–15 people, measured by Rysera's pipeline. The
  alert log already records what is needed to see which messages change behaviour.
