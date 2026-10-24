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
| Turn quickly in normal light | **L3** blurry (Debug `sharp` < 1.0) or **G1** | |
| Hold still facing a plain ceiling | **no L3** (v1.0 wrongly warned here) | |
| Point at a plain, well-lit wall from 1 m (no furniture in view) | **S1** point at a corner (or T1 if tracking drops). Debug `texture` should be < 0.15 | |
| Point at a shelf / poster wall | **no S1** (v1.0 wrongly warned here) | |
| Hold the phone still for 4 s | **G3** Keep moving forward (blue) | |
| Hand tremor while standing | **G2** Hold steady (may need threshold tuning) | |
| Cover the camera fully for 2 s | **T1** Tracking lost + a hint line; returns to Tracking after uncovering | |
| Walk slowly around a room for ~1 min | **C6** This scan looks good (green) and the Stop button turns green | |


### Phase 2 / 3 rules (v1.2)
| Do this | Expected banner | ✓ |
|---|---|---|
| Jerk the phone suddenly sideways | **G5** Move smoothly | |
| Hold still on a detailed scene with a smudged lens (thumb print) for 6 s | **L4** Clean the lens | |
| Point at something very close, then far, quickly | **L5** camera is focusing (if the phone reports AF state) | |
| Hold the phone 30 cm from a wall (depth phones only) | **S2** Too close | |
| Point down a long corridor > 4 m (depth phones only) | **S3** Too far | |
| Pan slowly past a mirror or glossy glass cabinet | **S4** Mirror or glass (experimental) | |
| After 15 s, keep facing one half of the room | **C1** Turn left ← / right → | |
| Never tilt up or down for 30 s | **C2** Look up ↑ / down ↓ | |
| Stand still facing one wall for > 3 s after 15 s | **C3** Film this from another spot; stripes visible on the wall | |
| Walk to one side of a ≥ 2×2 m room and stay | **C4** A corner is missing (after 45 s) | |
| Walk > 2.5 m away from the start, keep scanning 40 s | **C5** Walk back to where you started; disappears when you return | |
| Make it dark for 5 s, then light again | **T7** That part was poor — film it again (once) | |
| Turn **Voice** on | messages are spoken (English if no Sinhala voice) | |
| Turn **Stripes** off/on | pink stripes disappear/appear; they vanish where you walked | |
| Walk the room properly | status line shows "Seen from 2+ angles NN%", summary shows coverage and loop | |

- [ ] Never more than one message at a time; no message flickers away in under 1.5 s
- [ ] The same message does not repeat more than 3 times in 10 s; on the 3rd it shows a longer explanation
- [ ] Pose trail (bottom-left) draws the path you walked

## D. Summary, accept, re-record
- [ ] Summary shows verdict, duration, tracking losses, distance, viewpoints, video saved, warnings by rule, trail
- [ ] "Record again" deletes the capture and returns to the camera
- [ ] "Use this capture" → package path + size; "Share package" opens the share sheet (send to Drive/email)
- [ ] The zip contains: `session.mp4 poses.jsonl quality.jsonl alerts.jsonl coverage.json summary.json manifest.json status.json checksums.sha256`
- [ ] On a computer: `cd scan_<id> && sha256sum -c checksums.sha256` → every line `OK`
- [ ] `manifest.json` → `recording.videoWidth × videoHeight` (v1.0 was 640×480; v1.1 should be larger if the phone offers it)
- [ ] After sharing, "Delete from this phone" removes the capture
- [ ] `session.mp4` plays in a normal video player
- [ ] `manifest.json` has `formatVersion 1.2`, `depth.supported/used`, device model, camera fx/fy/cx/cy, `coverage.method POSE_TRAIL`, `thresholdsVersion`

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
