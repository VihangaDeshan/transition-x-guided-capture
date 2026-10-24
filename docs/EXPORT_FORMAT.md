# Export package format

`formatVersion: 1.2`. Platform-neutral (TDD 15): poses are carried explicitly, so the package does
not depend on reading the ARCore recording.

```
scan_<uuid>/
  session.mp4     ARCore Recording API MP4 (plays as a normal video; also replayable by ARCore)
  poses.jsonl     every frame: {"t":ns,"tx","ty","tz","qx","qy","qz","qw","tracking","failure"}   ← primary pose record
  quality.jsonl   every analysed frame: the full SignalFrame (see CONTRACTS.md)                    ← replay input
  alerts.jsonl    guidance events: SHOWN / DISMISSED (shownForMs) / SUPPRESSED (reason) / STATE, plus INTERRUPTION notes
  coverage.json   method (VOXEL_GRID | POINTCLOUD_ANGULAR | POSE_TRAIL), surfacesSeenFromTwoPlusAngles,
                  voxels [[x,y,z,parallaxDeg,hits], ...] (20 cm voxel centres), trail
  summary.json    verdict, durations, tracking losses, warnings by rule, suppressed by rule
  manifest.json   formatVersion, sessionId, device, camera intrinsics, session stats, recording status, versions
  status.json     written on accept: {"accepted":true,"acceptedAt":...,"transfer":"MANUAL"}
  checksums.sha256  written on accept: SHA-256 of every file above (`sha256sum -c checksums.sha256`)
```

The video resolution is the ARCore **CPU image** size (`recording.videoWidth/videoHeight`), not
the display texture. Frames are stored in sensor orientation (`camera.imageOrientation: "sensor"`).

Conventions: ARCore world frame (right-handed, +Y up, metres); `Camera.getPose()` (sensor pose);
timestamps are `Frame.getTimestamp()` nanoseconds in every stream. Intrinsics are for the CPU image
(`camera.intrinsicsFor = "cpu_image"`); the texture size used for recording is also given.

On the phone the package lives in `Android/data/lk.transitionx.guidedcapture/files/sessions/` and the
zip in `.../files/exports/`.

Coverage: a voxel is *covered* when it was seen along two directions (camera → voxel centre) at
least `minParallaxDeg` (default 15°) apart. `manifest.depth` says whether the Depth API was
supported and used.

## Changelog
- **1.2** (2026-10-24) — additive: real coverage (method, fraction, voxel list) in `coverage.json`,
  `manifest.coverage.surfacesSeenFromTwoPlusAngles`, `manifest.depth`, summary `coverageFraction` /
  `loopClosed`; `quality.jsonl` gains `angularAccel`, `afState`, `specularPercent`,
  `distanceFromStartM`, coverage and navigation fields. Older parsers keep working.
- **1.1** (2026-10-24) — additive: `checksums.sha256`; manifest `integrity`, `recording.videoWidth/videoHeight`,
  `camera.imageOrientation`; `quality.jsonl` gains `textureFraction` and `sharpness`. 1.0 parsers keep working.
- **1.0** (2026-10-24) — first release.
