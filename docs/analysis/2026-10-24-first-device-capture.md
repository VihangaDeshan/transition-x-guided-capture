# First on-device capture — analysis and v1.1 changes

**Capture:** first on-device session, Samsung Galaxy A55 5G (SM-A556E), Android 16 (API 36), ARCore 1.57,
app 1.0.0, thresholds `1.0-initial`, 83.5 s, furnished indoor room, accepted and shared via the share sheet.

Method: every stream in the package was analysed, the 640×480 video was decoded and the image
metrics recomputed from the recorded frames with the app's own `ImageMetrics` code, the session
was replayed through the decision engine, and frames at every warning were inspected by eye.
The recording is of a private home: no images, video or package files are published. Only
aggregate measurements are reported here.

## 1. What worked

| Check | Result |
|---|---|
| Sustained frame rate (NFR-2 ≥ 25 fps) | **30.0 fps**; frame interval p50 33.35 ms, p95 33.41 ms, max 46.3 ms; no gap > 50 ms |
| Backpressure | 2504 poses, 2504 SignalFrames — the analysis thread never fell behind; 626 image samples (every 4th frame) |
| Tracking | TRACKING for all 2504 frames; 0 failures, 0 losses |
| Thermal | status 0 throughout |
| Recording | ARCore Recording API MP4 written and playable: H.264 + IMU/data tracks (≈ 64 Hz sensor tracks) |
| Measurement validity | Blur and brightness recomputed from the video correlate **r = 0.994** and **r = 0.993** with the values the phone logged |
| Determinism (NFR-13) | Replaying `quality.jsonl` offline reproduced the phone's per-rule counts exactly |
| Package | manifest/summary/coverage/status written; intrinsics fx 438.8, fy 438.9, cx 318.4, cy 240.2 (640×480) |

## 2. Problems found

### 2.1 S1 "blank wall" was a false alarm (10 shows, one lasting 15.5 s)
The ARCore point cloud on this phone returned **0–11 points (p50 = 3, p95 = 39)** in a furnished
room while tracking never failed, so `featureCount < 20` was true for **87 %** of the session.
Visual inspection confirmed that the scene was well textured at those moments. The point count
does not measure texture on this device.

**Fix:** S1 now uses an image measure — `textureFraction`, the share of 8×8 blocks (on the 160×120
downsample) whose luminance standard deviation exceeds 8. On this session it is below 0.15 only
during the dark spell (33–45 s), which L1 already covers, so S1 also requires adequate brightness.
ARCore `INSUFFICIENT_FEATURES` still triggers S1 directly.

### 2.2 L3 "blurry" fired on sharp but plain surfaces
Raw Laplacian variance measures detail, not blur: a sharp, plain surface scored 117–136 (< 100 enter,
< 150 exit). The message says "move more slowly", which is wrong advice there.

**Fix:** `sharpness` = Laplacian variance ÷ (mean 8×8 block variance + 1), a contrast-normalised
measure that is also portable across camera gains. Median **0.67 while turning > 30 °/s vs 1.93
while still**. L3 now requires `sharpness < 1.0` (exit > 1.3) **and** motion (> 10 °/s or > 0.4 m/s).
Blur while still is a lens/focus problem (L4/L5, Phase 2), not "move slowly".

### 2.3 Same-tier messages took turns
S1 and L3 (both tier 2) replaced each other every ~1.5 s (the minimum display time), e.g. 18.0 →
19.5 → 23.5 → 25.0 s. **Fix:** a message that is still true stays until something *strictly* more
urgent appears.

### 2.4 Recording resolution is only 640×480
The camera config had a 1920×1080 texture, but the ARCore recording stores the **CPU image**
stream, which was 640×480 (8.5 Mbit/s). That limits reconstruction detail. **Fix:** camera config
selection now prefers the largest CPU image up to 1280×720 (then the largest texture ≤ 1080p), and the
manifest records `recording.videoWidth/videoHeight`. The preferred resolution is still an open
question for Rysera (TDD §23 Q3).

### 2.5 "This scan looks good" (C6) was never reached
Path 6.7 m and 41 keyframes, but the walk stayed within 1.22 m of the start (small room) against
the 1.5 m extent requirement. **Change:** extent lowered to 1.0 m (other conditions unchanged).

### 2.6 Storage estimate was 2.3× too pessimistic
Measured 90.8 MB / 83.5 s ≈ **1.09 MB/s** at 640×480. The pre-recording check now uses this figure,
scaled by the selected CPU image size, +25 %.

## 3. Before / after on the same session (replay)

| Rule | v1.0 shown | v1.1 shown | Ground truth from frames |
|---|---|---|---|
| S1 blank wall | 10 | **0** | textured scenes — false alarms removed |
| L3 blurry | 5 | 4 | now only during motion smear (32 s, 59 s, 72 s) |
| L1 too dark | 2 | 2 | genuinely dark 33–45 s, ~80 s |
| G1 turn slowly | 3 | 2 | real fast turns (78 °/s at 31 s) |
| G4 don't spin | 3 | 3 | standing still while rotating |
| G3 keep moving | 1 | 7 | median walking speed 0.04 m/s — previously hidden behind S1 |
| C6 looks good | 0 | 1 | reached at 77 s |
| Same-tier ping-pong | 6+ | **0** | |

The real session (with recomputed texture/sharpness) is a regression fixture:
`modules/guided-capture/android/src/test/resources/fixtures/a55-scan1-quality.jsonl.gz`, tested by
`RealCaptureTest`.

**Caveat:** one session, one device, labelled by eye. Thresholds `1.1-scan1` are *informed* by real
data, not calibrated; calibration still needs Rysera's pass/fail labels (TDD §19).

## 4. Next captures to record
1. A genuinely blank wall at 1 m in good light (confirms S1 enter 0.15).
2. A slow, correct walk around a larger room for 2–3 min (C6, G3 rate, thermal).
3. Deliberate fast pans in bright light (L3 vs G1 separation).
4. Same room on a second phone model (portability of `sharpness`).
