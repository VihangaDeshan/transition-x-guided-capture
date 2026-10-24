# On-device test checklist (Samsung Galaxy A55 5G)

Install the newest APK from GitHub → Releases. Tick each line. Turn on **Debug** (bottom-right on
the capture screen) to see the raw signals behind every message.

## A. Start-up and checks (FR-1, TDD 18)
- [ ] First launch shows the check screen with device name and API level
- [ ] Camera permission: deny once → explanation + "Allow camera" button; allow → green
- [ ] Google Play Services for AR shows `supported installed` (if not: "Install / update" opens Play Store; returning re-checks)
- [ ] Free storage shown; "Start scan" appears only when everything is green
- [ ] English ↔ සිංහල toggle switches all text

## B. Capture flow
- [ ] Start scan → camera preview appears full screen (not black, not stretched)
- [ ] Record → REC + timer; first message is **T2 "Move the phone slowly from side to side"** and nothing else
- [ ] After ~1–2 s of gentle movement, state pill turns **Tracking** and T2 disappears
- [ ] Stop → summary screen within ~2 s

## C. Each Phase 1 rule (do them deliberately)
| Do this | Expected banner | ✓ |
|---|---|---|
| Stand still in the middle and turn slowly on the spot for 3 s | **G4** Walk around the room, don't spin (red, long vibration) | |
| Turn very fast | **G1** Turn more slowly (or T6/T1 if tracking breaks) | |
| Cover most of the lens with a finger / very dark room | **L1** too dark | |
| Point at a bright window | **L2** too bright | |
| Shake the phone while walking fast | **L3** blurry | |
| Point at a blank wall from 30 cm | **S1** point at a corner (or T1 if tracking drops) | |
| Hold the phone still for 4 s | **G3** Keep moving forward (blue) | |
| Hand tremor while standing | **G2** Hold steady (may need threshold tuning) | |
| Cover the camera fully for 2 s | **T1** Tracking lost + a hint line; returns to Tracking after uncovering | |
| Walk slowly around a room for ~1 min | **C6** This scan looks good (green) and the Stop button turns green | |

- [ ] Never more than one message at a time; no message flickers away in under 1.5 s
- [ ] The same message does not repeat more than 3 times in 10 s; on the 3rd it shows a longer explanation
- [ ] Pose trail (bottom-left) draws the path you walked

## D. Summary, accept, re-record
- [ ] Summary shows verdict, duration, tracking losses, distance, viewpoints, video saved, warnings by rule, trail
- [ ] "Record again" deletes the capture and returns to the camera
- [ ] "Use this capture" → package path + size; "Share package" opens the share sheet (send to Drive/email)
- [ ] The zip contains: `session.mp4 poses.jsonl quality.jsonl alerts.jsonl coverage.json summary.json manifest.json status.json`
- [ ] `session.mp4` plays in a normal video player
- [ ] `manifest.json` has `formatVersion 1.0`, device model, camera fx/fy/cx/cy, `coverage.method POSE_TRAIL`, `thresholdsVersion`

## E. Robustness (NFR)
- [ ] Press Home during recording, come back → session continues (T1 then Tracking), summary shows 1 interruption
- [ ] Back button during recording does nothing
- [ ] 10-minute scan: auto-stops at the limit with a note; "One minute left" shown at 9:00
- [ ] Phone warm after 10 min? Note thermal value in Debug (`thermal`) — T3 appears at SEVERE

## Record for the report
| Item | Value |
|---|---|
| Sustained FPS feel (smooth / choppy) | |
| Session length before thermal warning | |
| Typical blur score: sharp / blurry | |
| Typical feature count: furnished room / blank wall | |
| Bugs seen | |
